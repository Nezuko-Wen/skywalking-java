/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.agent;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.SWAgentBuilderDefault;
import net.bytebuddy.agent.builder.SWDescriptionStrategy;
import net.bytebuddy.agent.builder.SWNativeMethodStrategy;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import org.apache.skywalking.apm.agent.bytebuddy.SWAuxiliaryTypeNamingStrategy;
import net.bytebuddy.implementation.SWImplementationContextFactory;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.apache.skywalking.apm.agent.bytebuddy.SWMethodGraphCompilerDelegate;
import org.apache.skywalking.apm.agent.bytebuddy.SWMethodNameTransformer;
import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.SnifferConfigInitializer;
import org.apache.skywalking.apm.agent.core.jvm.LoadedLibraryCollector;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.EnhanceContext;
import org.apache.skywalking.apm.agent.core.plugin.InstrumentDebuggingClass;
import org.apache.skywalking.apm.agent.core.plugin.PluginBootstrap;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.PluginFinder;
import org.apache.skywalking.apm.agent.core.plugin.bootstrap.BootstrapInstrumentBoost;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.DelegateNamingResolver;
import org.apache.skywalking.apm.agent.core.plugin.jdk9module.JDK9ModuleExporter;

import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static org.apache.skywalking.apm.agent.core.conf.Constants.NAME_TRAIT;

/**
 * The main entrance of sky-walking agent, based on javaagent mechanism.
 */
public class SkyWalkingAgent {
    private static ILog LOGGER = LogManager.getLogger(SkyWalkingAgent.class);

    /**
     * Main entrance. Use byte-buddy transform to enhance all classes, which define in plugins.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) throws PluginException {
        final PluginFinder pluginFinder;
        try {
            // 1.加载配置
            SnifferConfigInitializer.initializeCoreConfig(agentArgs);
        } catch (Exception e) {
            // try to resolve a new logger, and use the new logger to write the error log here
            LogManager.getLogger(SkyWalkingAgent.class)
                    .error(e, "SkyWalking agent initialized failure. Shutting down.");
            return;
        } finally {
            // refresh logger again after initialization finishes
            LOGGER = LogManager.getLogger(SkyWalkingAgent.class);
        }

        if (!Config.Agent.ENABLE) {
            LOGGER.warn("SkyWalking agent is disabled.");
            return;
        }

        try {
            // 2.加载插件
            pluginFinder = new PluginFinder(new PluginBootstrap().loadPlugins());
        } catch (AgentPackageNotFoundException ape) {
            LOGGER.error(ape, "Locate agent.jar failure. Shutting down.");
            return;
        } catch (Exception e) {
            LOGGER.error(e, "SkyWalking agent initialized failure. Shutting down.");
            return;
        }

        try {
            // 注入增强字节码
            installClassTransformer(instrumentation, pluginFinder);
        } catch (Exception e) {
            LOGGER.error(e, "Skywalking agent installed class transformer failure.");
        }

        try {
            // 3.启动上报等核心服务
            ServiceManager.INSTANCE.boot();
        } catch (Exception e) {
            LOGGER.error(e, "Skywalking agent boot failure.");
        }

        Runtime.getRuntime()
               .addShutdownHook(new Thread(ServiceManager.INSTANCE::shutdown, "skywalking service shutdown thread"));
    }

    static void installClassTransformer(Instrumentation instrumentation, PluginFinder pluginFinder) throws Exception {
        LOGGER.info("Skywalking agent begin to install transformer ...");

        AgentBuilder agentBuilder = newAgentBuilder().ignore(
            nameStartsWith("net.bytebuddy.")
                .or(nameStartsWith("org.slf4j."))
                .or(nameStartsWith("org.groovy."))
                .or(nameContains("javassist"))
                .or(nameContains(".asm."))
                .or(nameContains(".reflectasm."))
                .or(nameStartsWith("sun.reflect"))
                .or(allSkyWalkingAgentExcludeToolkit())
                .or(ElementMatchers.isSynthetic()));

        JDK9ModuleExporter.EdgeClasses edgeClasses = new JDK9ModuleExporter.EdgeClasses();
        try {
            // 增强BootStrap类
            agentBuilder = BootstrapInstrumentBoost.inject(pluginFinder, instrumentation, agentBuilder, edgeClasses);
        } catch (Exception e) {
            throw new Exception("SkyWalking agent inject bootstrap instrumentation failure. Shutting down.", e);
        }

        try {
            // 兼容jdk9+
            agentBuilder = JDK9ModuleExporter.openReadEdge(instrumentation, agentBuilder, edgeClasses);
        } catch (Exception e) {
            throw new Exception("SkyWalking agent open read edge in JDK 9+ failure. Shutting down.", e);
        }

        agentBuilder.type(pluginFinder.buildMatch())
                    // 注入增强字节码
                    .transform(new Transformer(pluginFinder))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new RedefinitionListener())
                    .with(new Listener())
                    .installOn(instrumentation);

        PluginFinder.pluginInitCompleted();

        LOGGER.info("Skywalking agent transformer has installed.");
    }

    /**
     * Create a new agent builder through customized {@link ByteBuddy} powered by
     * {@link SWAuxiliaryTypeNamingStrategy} {@link DelegateNamingResolver} {@link SWMethodNameTransformer} and {@link SWImplementationContextFactory}
     */
    private static AgentBuilder newAgentBuilder() {
        final ByteBuddy byteBuddy = new ByteBuddy()
                .with(TypeValidation.of(Config.Agent.IS_OPEN_DEBUGGING_CLASS))
                .with(new SWAuxiliaryTypeNamingStrategy(NAME_TRAIT))
                .with(new SWImplementationContextFactory(NAME_TRAIT))
                .with(new SWMethodGraphCompilerDelegate(MethodGraph.Compiler.DEFAULT));

        return new SWAgentBuilderDefault(byteBuddy, new SWNativeMethodStrategy(NAME_TRAIT))
                .with(new SWDescriptionStrategy(NAME_TRAIT));
    }

    private static class Transformer implements AgentBuilder.Transformer {
        private PluginFinder pluginFinder;

        Transformer(PluginFinder pluginFinder) {
            this.pluginFinder = pluginFinder;
        }

        @Override
        public DynamicType.Builder<?> transform(final DynamicType.Builder<?> builder,
                                                final TypeDescription typeDescription,
                                                final ClassLoader classLoader,
                                                final JavaModule javaModule,
                                                final ProtectionDomain protectionDomain) {
            LoadedLibraryCollector.registerURLClassLoader(classLoader);
            // 过滤匹配的插件
            List<AbstractClassEnhancePluginDefine> pluginDefines = pluginFinder.find(typeDescription);
            if (pluginDefines.size() > 0) {
                DynamicType.Builder<?> newBuilder = builder;
                EnhanceContext context = new EnhanceContext();
                for (AbstractClassEnhancePluginDefine define : pluginDefines) {
                    DynamicType.Builder<?> possibleNewBuilder = define.define(
                        typeDescription, newBuilder, classLoader, context);
                    if (possibleNewBuilder != null) {
                        newBuilder = possibleNewBuilder;
                    }
                }
                if (context.isEnhanced()) {
                    LOGGER.debug("Finish the prepare stage for {}.", typeDescription.getName());
                }

                return newBuilder;
            }

            LOGGER.debug("Matched class {}, but ignore by finding mechanism.", typeDescription.getTypeName());
            return builder;
        }
    }

    private static ElementMatcher.Junction<NamedElement> allSkyWalkingAgentExcludeToolkit() {
        return nameStartsWith("org.apache.skywalking.").and(not(nameStartsWith("org.apache.skywalking.apm.toolkit.")));
    }

    private static class Listener implements AgentBuilder.Listener {
        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {

        }

        @Override
        public void onTransformation(final TypeDescription typeDescription,
                                     final ClassLoader classLoader,
                                     final JavaModule module,
                                     final boolean loaded,
                                     final DynamicType dynamicType) {
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("On Transformation class {}.", typeDescription.getName());
            }

            InstrumentDebuggingClass.INSTANCE.log(dynamicType);
        }

        @Override
        public void onIgnored(final TypeDescription typeDescription,
                              final ClassLoader classLoader,
                              final JavaModule module,
                              final boolean loaded) {

        }

        @Override
        public void onError(final String typeName,
                            final ClassLoader classLoader,
                            final JavaModule module,
                            final boolean loaded,
                            final Throwable throwable) {
            LOGGER.error("Enhance class " + typeName + " error.", throwable);
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
        }
    }

    private static class RedefinitionListener implements AgentBuilder.RedefinitionStrategy.Listener {

        @Override
        public void onBatch(int index, List<Class<?>> batch, List<Class<?>> types) {
            /* do nothing */
        }

        @Override
        public Iterable<? extends List<Class<?>>> onError(int index,
                                                          List<Class<?>> batch,
                                                          Throwable throwable,
                                                          List<Class<?>> types) {
            LOGGER.error(throwable, "index={}, batch={}, types={}", index, batch, types);
            return Collections.emptyList();
        }

        @Override
        public void onComplete(int amount, List<Class<?>> types, Map<List<Class<?>>, Throwable> failures) {
            /* do nothing */
        }
    }
}
