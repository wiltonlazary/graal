/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DetectedChange {
    private final Map<Method, ParserMethod> changedMethodBodies = new HashMap<>();
    private final List<ParserMethod> addedMethods = new ArrayList<>();
    private final Set<Method> removedMethods = new HashSet<>();
    private final List<Field> outerFields = new ArrayList<>();

    void addMethodBodyChange(Method oldMethod, ParserMethod newMethod) {
        changedMethodBodies.put(oldMethod, newMethod);
    }

    Map<Method, ParserMethod> getChangedMethodBodies() {
        return Collections.unmodifiableMap(changedMethodBodies);
    }

    List<ParserMethod> getAddedMethods() {
        return Collections.unmodifiableList(addedMethods);
    }

    Set<Method> getRemovedMethods() {
        return Collections.unmodifiableSet(removedMethods);
    }

    public void addNewMethod(ParserMethod method) {
        addedMethods.add(method);
    }

    public void addRemovedMethods(Method method) {
        removedMethods.add(method);
    }

    public void addRemovedMethods(List<Method> methods) {
        removedMethods.addAll(methods);
    }

    public void addNewMethods(List<ParserMethod> newMethods) {
        addedMethods.addAll(newMethods);
    }

    public void addOuterField(Field oldField) {
        outerFields.add(oldField);
    }

    public List<Field> getOuterFields() {
        return Collections.unmodifiableList(outerFields);
    }
}
