/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.particleframework.inject.annotation;

import org.particleframework.core.annotation.Internal;
import org.particleframework.core.convert.value.ConvertibleValues;
import org.particleframework.core.reflect.ClassUtils;
import org.particleframework.core.reflect.InstantiationUtils;
import org.particleframework.core.reflect.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Support method for {@link org.particleframework.core.annotation.AnnotationMetadata}
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Internal
class AnnotationMetadataSupport {


    private static final Map<Class<? extends Annotation>, Optional<Constructor<InvocationHandler>>> ANNOTATION_PROXY_CACHE = new ConcurrentHashMap<>(20);
    private static final Map<String, Map<String, Object>> ANNOTATION_DEFAULTS = new ConcurrentHashMap<>(20);

    @SuppressWarnings("unchecked")
    static Map<String, Object> getDefaultValues(String annotation) {
        Optional<Class> cls = ClassUtils.forName(annotation, AnnotationMetadataSupport.class.getClassLoader());
        return cls.map((Function<Class, Map>) AnnotationMetadataSupport::getDefaultValues).orElseGet(Collections::emptyMap);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> getDefaultValues(Class<? extends Annotation> annotation) {
        return ANNOTATION_DEFAULTS.computeIfAbsent(annotation.getName().intern(), aClass -> {
            Map<String, Object> defaultValues = new LinkedHashMap<>();
            Method[] declaredMethods = annotation.getDeclaredMethods();
            for (Method declaredMethod : declaredMethods) {
                Object defaultValue = declaredMethod.getDefaultValue();
                if(defaultValue != null) {
                    defaultValues.put(declaredMethod.getName().intern(), defaultValue);
                }
            }
            return defaultValues;
        });
    }

    @SuppressWarnings("unchecked")
    static Optional<Constructor<InvocationHandler>> getProxyClass(Class<? extends Annotation> annotation) {
        return ANNOTATION_PROXY_CACHE.computeIfAbsent(annotation, aClass -> {
            Class proxyClass = Proxy.getProxyClass(annotation.getClassLoader(), annotation);
            return ReflectionUtils.findConstructor(proxyClass, InvocationHandler.class);
        });
    }

    static <T extends Annotation> T buildAnnotation(Class<T> annotationClass, ConvertibleValues<Object> annotationValues) {
        Optional<Constructor<InvocationHandler>> proxyClass = getProxyClass(annotationClass);
        if(proxyClass.isPresent()) {
            Optional instantiated = InstantiationUtils.tryInstantiate(proxyClass.get(), (InvocationHandler) (proxy, method, args) -> {
                if(annotationValues.contains(method.getName())) {
                    Optional<?> result = annotationValues.get(method.getName(), method.getReturnType());
                    if(result.isPresent()) {
                        return result.get();
                    }
                }
                return method.getDefaultValue();
            });
            if(instantiated.isPresent()) {
                return (T) instantiated.get();
            }
        }
        throw new AnnotationMetadataException("Failed to build annotation for type: " + annotationClass.getName());
    }
}
