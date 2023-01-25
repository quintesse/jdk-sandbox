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
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static jdk.internal.org.objectweb.asm.ClassReader.EXPAND_FRAMES;

/**
 *
 * Dump resources plugin
 */
public final class SealPlugin extends AbstractPlugin {

    public SealPlugin() {
        super("seal");
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        TypeSubclasses gts = mapTypeSubclasses(in);
        ModuleClasses gmc = mapModuleClasses(in);

        Stats stats = new Stats();
        in.transformAndCopy(r -> {
            if (isClass(r)) {
                transform(gts, gmc, r, stats);
            }
            return r;
        }, out);

        System.out.println("Seal plugin:");
        stats.print();

        return out.build();
    }

    // Create a mapping of types to their subclasses
    private TypeSubclasses mapTypeSubclasses(ResourcePool in) {
        TypeSubclasses gts = new TypeSubclasses();
        in.entries().forEach(r -> {
            if (isClass(r)) {
                String path = r.path();
                ClassReader cr = newClassReader(path, r);
                String name = internalClassName(path);
                gts.addClass(cr.getSuperName(), name);
                for (String iface : cr.getInterfaces()) {
                    gts.addClass(iface, name);
                }
            }
        });
        return gts;
    }

    // Create a mapping of modules to their classes
    private ModuleClasses mapModuleClasses(ResourcePool in) {
        ModuleClasses gts = new ModuleClasses();
        in.entries().forEach(r -> {
            if (isClass(r)) {
                String name = internalClassName(r.path());
                gts.addClass(r.moduleName(), name);
            }
        });
        return gts;
    }

    private void transform(TypeSubclasses gts, ModuleClasses gmc, ResourcePoolEntry r, Stats stats) {
        String path = r.path();
        String name = internalClassName(path);
        ClassReader cr = newClassReader(path, r);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        boolean modified = false;
        Set<String> subclasses = gts.getOrDefault(name, Collections.emptySet());
        if (subclasses.isEmpty()) {
            stats.noSubclasses++;
            if ((cn.access & Opcodes.ACC_FINAL) != 0) {
                // No subclasses and not final, we can mark this class final
                //cn.access |= Opcodes.ACC_FINAL;
                modified = true;
                stats.notFinal++;
            }
        } else {
            if (cn.permittedSubclasses == null || cn.permittedSubclasses.isEmpty()) {
                stats.notSealed++;
                // Has subclasses, check if they're all in our module
                if (gmc.hasClasses(r.moduleName(), subclasses)) {
                    //cn.permittedSubclasses.addAll(subclasses);
                    modified = true;
                    stats.localSubclassesOnly++;
                }
            }
        }
        stats.total++;
    }

    // Given class path this removes the module name and the ".class" extension
    private static String internalClassName(String path) {
        return path.substring(path.indexOf('/', 1) + 1, path.length() - ".class".length());
    }

    private static boolean isClass(ResourcePoolEntry r) {
        return r.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)
                && r.path().endsWith(".class")
                && !r.path().endsWith("module-info.class");
    }

    @SuppressWarnings("serial")
    private static class CategoryClasses extends HashMap<String, Set<String>> {
        public void addClass(String categoryName, String className) {
            Set<String> classes = get(categoryName);
            if (classes == null) {
                put(categoryName, classes = new HashSet<>());
            }
            classes.add(className);
        }
    }

    @SuppressWarnings("serial")
    private static class TypeSubclasses extends CategoryClasses {}

    @SuppressWarnings("serial")
    private static class ModuleClasses extends CategoryClasses {
        public boolean hasClasses(String moduleName, Collection<String> classNames) {
            Set<String> moduleClasses = getOrDefault(moduleName, Collections.emptySet());
            return classNames.stream().allMatch(moduleClasses::contains);
        }
    }

    private static class Stats {
        public int total;
        public int noSubclasses;
        public int notFinal;
        public int notSealed;
        public int localSubclassesOnly;

        public void print() {
            System.out.printf("#Classes found: %d\n", total);
            System.out.printf("#Classes without subclasses: %d\n", noSubclasses);
            System.out.printf("#Classes not already final: %d\n", notFinal);
            System.out.printf("#Classes with subclasses: %d\n", total - noSubclasses);
            System.out.printf("#Classes not already sealed: %d\n", notSealed);
            System.out.printf("#Classes with only local subclasses: %d\n", localSubclassesOnly);
        }
    }
}
