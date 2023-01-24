/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package jdk.tools.jlink.internal.plugins;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 *
 * Dump resources plugin
 */
public final class SealPlugin extends AbstractPlugin {
    private final TypeSubclasses globalTypeSubclasses = new TypeSubclasses();
    private final Map<String, TypeSubclasses> moduleTypeSubclasses = new HashMap<>();

    public SealPlugin() {
        super("seal");
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        // Create a mapping of types to their subclasses, grouped by module
        in.entries().forEach(r -> {
            if (r.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)) {
                String path = r.path();
                if (path.endsWith(".class") && !path.endsWith("module-info.class")) {
                    TypeSubclasses ts = typeSubclassesByModule(r.moduleName());
                    ClassReader reader = newClassReader(path, r);
                    String name = fullClassName(path);
                    globalTypeSubclasses.addSubclass(reader.getSuperName(), name);
                    ts.addSubclass(reader.getSuperName(), name);
                    for (String iface : reader.getInterfaces()) {
                        globalTypeSubclasses.addSubclass(iface, name);
                        ts.addSubclass(iface, name);
                    }
                }
            }
        });

        // Find all types that have no subclasses, they can be marked final
        in.entries().forEach(r -> {
            if (r.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)) {
                String path = r.path();
                if (path.endsWith(".class") && !path.endsWith("module-info.class")) {
                    String name = fullClassName(path);
                    Set<String> subclasses = globalTypeSubclasses.getOrDefault(name, Collections.emptySet());
                    if (subclasses.isEmpty()) {
                        System.out.printf("MARK FINAL %s\n", name);
                    }
                }
            }
        });

        //in.transformAndCopy(Function.identity(), out);
        //return out.build();
        return in;
    }

    private TypeSubclasses typeSubclassesByModule(String moduleName) {
        TypeSubclasses ts = moduleTypeSubclasses.get(moduleName);
        if (ts == null) {
            moduleTypeSubclasses.put(moduleName, ts = new TypeSubclasses());
        }
        return ts;
    }

    // Given internal class name this returns the package
    private static String getPackage(String binaryName) {
        int index = binaryName.lastIndexOf("/");

        return index == -1 ? "" : binaryName.substring(0, index);
    }

    // Given full class name this removes the module name
    private static String internalClassName(String fullClassName) {
        return fullClassName.substring(fullClassName.indexOf('/') + 1);
    }

    // Given class path this removes ".class" but leaves module
    private static String fullClassName(String path) {
        return path.substring(1, path.length() - ".class".length());
    }

    @SuppressWarnings("serial")
    private static class TypeSubclasses extends HashMap<String, Set<String>> {
        public TypeSubclasses() {
        }

        public void addSubclass(String parentClassName, String childClassName) {
            Set<String> subclasses = get(parentClassName);
            if (subclasses == null) {
                put(parentClassName, subclasses = new HashSet<>());
            }
            subclasses.add(childClassName);
        }
    }
}
