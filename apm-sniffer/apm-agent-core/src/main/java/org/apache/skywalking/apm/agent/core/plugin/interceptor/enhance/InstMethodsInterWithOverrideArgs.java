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

package org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance;

import java.lang.reflect.Method;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Morph;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.loader.InterceptorInstanceLoader;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.so11y.AgentSo11y;

/**
 * The actual byte-buddy's interceptor to intercept class instance methods. In this class, it provides a bridge between
 * byte-buddy and sky-walking plugin.
 */
public class InstMethodsInterWithOverrideArgs {
    private static final ILog LOGGER = LogManager.getLogger(InstMethodsInterWithOverrideArgs.class);

    private static final String INTERCEPTOR_TYPE = "inst";

    private String pluginName;
    /**
     * An {@link InstanceMethodsAroundInterceptor} This name should only stay in {@link String}, the real {@link Class}
     * type will trigger classloader failure. If you want to know more, please check on books about Classloader or
     * Classloader appointment mechanism.
     */
    private InstanceMethodsAroundInterceptor interceptor;

    /**
     * @param instanceMethodsAroundInterceptorClassName class full name.
     */
    public InstMethodsInterWithOverrideArgs(String pluginName, String instanceMethodsAroundInterceptorClassName, ClassLoader classLoader) {
        this.pluginName = pluginName;
        try {
            interceptor = InterceptorInstanceLoader.load(instanceMethodsAroundInterceptorClassName, classLoader);
        } catch (Throwable t) {
            throw new PluginException("Can't create InstanceMethodsAroundInterceptor.", t);
        }
    }

    /**
     * Intercept the target instance method.
     *
     * @param obj          target class instance.
     * @param allArguments all method arguments
     * @param method       method description.
     * @param zuper        the origin call ref.
     * @return the return value of target instance method.
     * @throws Exception only throw exception because of zuper.call() or unexpected exception in sky-walking ( This is a
     *                   bug, if anything triggers this condition ).
     */
    // 通过@RuntimeType注解,能够动态匹配目标类的所有方法,从而进行拦截
    @RuntimeType
    public Object intercept(@This Object obj, @AllArguments Object[] allArguments, @Origin Method method,
        @Morph OverrideCallable zuper) throws Throwable {
        EnhancedInstance targetObject = (EnhancedInstance) obj;

        long interceptorTimeCost = 0L;
        long startTimeOfMethodBeforeInter = System.nanoTime();
        MethodInterceptResult result = new MethodInterceptResult();
        try {
            interceptor.beforeMethod(targetObject, method, allArguments, method.getParameterTypes(), result);
        } catch (Throwable t) {
            LOGGER.error(t, "class[{}] before method[{}] intercept failure", obj.getClass(), method.getName());
            AgentSo11y.errorOfPlugin(pluginName, INTERCEPTOR_TYPE);
        }
        interceptorTimeCost += System.nanoTime() - startTimeOfMethodBeforeInter;

        Object ret = null;
        try {
            if (!result.isContinue()) {
                ret = result._ret();
            } else {
                ret = zuper.call(allArguments);
            }
        } catch (Throwable t) {
            long startTimeOfMethodHandleExceptionInter = System.nanoTime();
            try {
                interceptor.handleMethodException(targetObject, method, allArguments, method.getParameterTypes(), t);
            } catch (Throwable t2) {
                LOGGER.error(t2, "class[{}] handle method[{}] exception failure", obj.getClass(), method.getName());
                AgentSo11y.errorOfPlugin(pluginName, INTERCEPTOR_TYPE);
            }
            interceptorTimeCost += System.nanoTime() - startTimeOfMethodHandleExceptionInter;
            throw t;
        } finally {
            long startTimeOfMethodAfterInter = System.nanoTime();
            try {
                ret = interceptor.afterMethod(targetObject, method, allArguments, method.getParameterTypes(), ret);
            } catch (Throwable t) {
                LOGGER.error(t, "class[{}] after method[{}] intercept failure", obj.getClass(), method.getName());
                AgentSo11y.errorOfPlugin(pluginName, INTERCEPTOR_TYPE);
            }
            interceptorTimeCost += System.nanoTime() - startTimeOfMethodAfterInter;
        }
        AgentSo11y.durationOfInterceptor(interceptorTimeCost);
        return ret;
    }
}
