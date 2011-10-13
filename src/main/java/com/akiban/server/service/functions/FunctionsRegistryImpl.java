/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.service.functions;

import com.akiban.server.aggregation.AggregatorFactory;
import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.error.NoSuchFunctionException;
import com.akiban.server.expression.ExpressionComposer;
import com.akiban.server.service.Service;
import com.akiban.server.service.jmx.JmxManageable;
import com.akiban.server.types.AkType;
import com.google.inject.Singleton;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Singleton
public final class FunctionsRegistryImpl implements FunctionsRegistry, Service<FunctionsRegistry>, JmxManageable {

    // AggregatorRegistry interface

    @Override
    public AggregatorFactory get(String name, AkType type) {
        Map<AkType,AggregatorFactory> factoryMap = getSafe(roAggregators, name);
        return getSafe(factoryMap, type, name);
    }

    // ExpressionRegistry interface

    @Override
    public ExpressionComposer composer(String name) {
        return getSafe(roComposers, name);
    }

    // FunctionsRegistry interface
    @Inject @SuppressWarnings("unused") // guice will use this
    public FunctionsRegistryImpl() {
        this(new GlobularFunctionsClassFinder());
    }

    // Service interface

    @Override
    public FunctionsRegistry cast() {
        return this;
    }

    @Override
    public Class<FunctionsRegistry> castClass() {
        return FunctionsRegistry.class;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void crash() {
    }

    // JmxManageable interface

    @Override
    public JmxObjectInfo getJmxObjectInfo() {
        return new JmxObjectInfo("SqlFunctions", new JmxBean(), FunctionsRegistryMXBean.class);
    }

    // for use in this package

    FunctionsRegistryImpl(FunctionsClassFinder finder) {
        Map<String,Map<AkType,AggregatorFactory>> aggregators = new HashMap<String, Map<AkType, AggregatorFactory>>();
        Map<String,ExpressionComposer> composers = new HashMap<String, ExpressionComposer>();
        for (Class<?> cls : finder.findClasses()) {
            if (!Modifier.isPublic(cls.getModifiers()))
                continue;
            findComposers(composers, cls);
            findAggregators(aggregators, cls);
        }
        this.roAggregators = aggregators;
        this.roComposers = composers;
    }

    Map<String,Map<AkType,AggregatorFactory>> getAllAggregators() {
        return Collections.unmodifiableMap(roAggregators);
    }

    Map<String,ExpressionComposer> getAllComposers() {
        return Collections.unmodifiableMap(roComposers);
    }

    // for use in this class

    private static void complain(String complaint) {
        throw new FunctionsRegistryException(complaint);
    }

    private static void findAggregators(Map<String, Map<AkType, AggregatorFactory>> composers, Class<?> cls) {
        for (Method method : cls.getDeclaredMethods()) {
            Aggregate annotation = method.getAnnotation(Aggregate.class);
            if (annotation != null) {
                validate(method);
                Map<AkType, AggregatorFactory> innerMap = new EnumMap<AkType, AggregatorFactory>(AkType.class);
                String name = nameIsAvailable(composers, annotation.value());
                composers.put(name, innerMap);
                for (AkType akType : AkType.values()) {
                    if (akType == AkType.UNSUPPORTED)
                        continue;
                    try {
                        AggregatorFactory factory = (AggregatorFactory) method.invoke(null, name, akType);
                        if (factory != null)
                            innerMap.put(akType, factory);
                    } catch (Exception e) {
                        throw new AkibanInternalException(
                                "while getting AggregatorFactory for " + akType + " from " + method,
                                e
                        );
                    }
                }
            }
        }
    }

    private static void findComposers(Map<String, ExpressionComposer> composers, Class<?> cls) {
        for (Field field : cls.getDeclaredFields()) {
            Scalar annotation = field.getAnnotation(Scalar.class);
            if (annotation != null) {
                validate(field);
                String name = nameIsAvailable(composers, annotation.value());
                try {
                    ExpressionComposer composer = (ExpressionComposer) field.get(null);
                    ExpressionComposer old = composers.put(name, composer);
                    assert old == null : old;
                } catch (IllegalAccessException e) {
                    throw new AkibanInternalException("while accessing field " + field, e);
                }
            }
        }
    }

    private static <V> V getSafe(Map<String, ? extends V> map, String key) {
        key = normalize(key);
        return getSafe(map, key, key);
    }

    private static <K,V> V getSafe(Map<? super K, ? extends V> map, K key, String name) {
        V result = map.get(key);
        if (result == null) {
            throw new NoSuchFunctionException(name);
        }
        return result;
    }
    
    private static String nameIsAvailable(Map<String,?> map, String name) {
        name = normalize(name);
        if (map.containsKey(name))
            complain("duplicate expression name: " + name);
        return name;
    }

    private static String normalize(String in) {
        return in.toLowerCase();
    }

    private static void validate(Method method) {
        validateMember(method);
        if(!Arrays.equals(AGGREGATE_FACTORY_PROVIDER_PARAMS, method.getParameterTypes()))
            complain("method " + method + " takes wrong param types");
        if (!AggregatorFactory.class.isAssignableFrom(method.getReturnType()))
            complain("method " + method + " must return " + AggregatorFactory.class.getSimpleName());
    }

    private static void validate(Field field) {
        validateMember(field);
        if (!ExpressionComposer.class.isAssignableFrom(field.getType()))
            complain("field " + field + " isn't a subclass of " + ExpressionComposer.class.getSimpleName());
    }

    private static void validateMember(Member member) {
        int modifiers = member.getModifiers();
        if (! (
                Modifier.isStatic(modifiers)
                        && ((member instanceof Method) || Modifier.isFinal(modifiers))
                        && Modifier.isPublic(modifiers)
        )) {
            complain(member.getClass().getSimpleName() + " " + member + " must be a public final static");
        }
    }

    // object state

    private final Map<String,Map<AkType,AggregatorFactory>> roAggregators;
    private final Map<String,ExpressionComposer> roComposers ;

    // class state

    private static final Class<?>[] AGGREGATE_FACTORY_PROVIDER_PARAMS = { String.class, AkType.class };

    // nested classes

    public static class FunctionsRegistryException extends AkibanInternalException {
        public FunctionsRegistryException(String message) {
            super(message);
        }
    }

    private class JmxBean implements FunctionsRegistryMXBean {
        @Override
        public List<String> getScalars() {
            return sortedList(roComposers.keySet());
        }

        @Override
        public List<String> getAggregates() {
            return sortedList(roAggregators.keySet());
        }

        @Override
        public Map<String, Set<AkType>> getAggregatesWithTypes() {
            Map<String,Map<AkType,AggregatorFactory>> aggregators = roAggregators;
            Map<String,Set<AkType>> result = new TreeMap<String, Set<AkType>>();
            for (Map.Entry<String,Map<AkType,AggregatorFactory>> entry : aggregators.entrySet()) {
                result.put(entry.getKey(), entry.getValue().keySet());
            }
            return result;
        }

        private <T extends Comparable<T>> List<T> sortedList(Collection<? extends T> collection) {
            List<T> result = new ArrayList<T>(collection);
            Collections.sort(result);
            return result;
        }
    }
}