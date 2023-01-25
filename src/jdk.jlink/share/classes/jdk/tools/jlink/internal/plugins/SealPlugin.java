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
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;
import jdk.tools.jlink.plugin.ResourcePoolModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.Logger.Level.WARNING;

/**
 *
 * Dump resources plugin
 */
public final class SealPlugin extends AbstractPlugin {

    public SealPlugin() {
        super("seal");
    }

    private Set<String> acceptedModules = null;
    private boolean markFinal = true;
    private boolean markSealed = true;

    private static System.Logger.Level logLevel = WARNING;

    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public void configure(Map<String, String> config) {
        String mods = config.get(getName());
        if (!mods.equals("*")) {
            acceptedModules = new HashSet<>(Arrays.asList(mods.split(",")));
        }
        markFinal = config.getOrDefault("final", "y").equals("y");
        markSealed = config.getOrDefault("sealed", "y").equals("y");
        logLevel = System.Logger.Level.valueOf(config.getOrDefault("log", "WARNING").toUpperCase());
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        TypeSubclasses gts = mapTypeSubclasses(in);
        ModuleClasses gmc = mapModuleClasses(in);

        Stats stats = new Stats();
        in.transformAndCopy(r -> {
            log(TRACE, "Analyzing resource %s", r.path());

            if (isClass(r)) {
                r = transform(r, in, gts, gmc, stats);
            }

            return r;
        }, out);

        if (isLoggable(INFO)) {
            stats.print();
        }

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

    private ResourcePoolEntry transform(ResourcePoolEntry r, ResourcePool pool, TypeSubclasses gts, ModuleClasses gmc, Stats stats) {
        if (acceptedModules != null && !acceptedModules.contains(r.moduleName())) {
            return r;
        }

        String name = internalClassName(r.path());
        ClassReader cr = newClassReader(r.path(), r);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        boolean modified = false;
        Set<String> subclasses = gts.getOrDefault(name, Collections.emptySet());
        if (subclasses.isEmpty()) {
            if ((cn.access & Opcodes.ACC_INTERFACE) == 0) {
                stats.noSubclasses++;
                if ((cn.access & Opcodes.ACC_FINAL) == 0) {
                    // No subclasses and not final, we can mark this class final
                    if (markFinal) {
                        cn.access |= Opcodes.ACC_FINAL;
                        log(DEBUG, "Marking %s as final", name);
                        modified = true;
                        stats.notFinal++;
                    }
                }
            }
        } else {
            stats.withSubclasses++;
            if (cn.permittedSubclasses == null || cn.permittedSubclasses.isEmpty()) {
                stats.notSealed++;
                // Has subclasses, check if they're all in our module
                ResourcePoolModule module = pool.moduleView().findModule(r.moduleName()).orElse(null);
                if (gmc.hasClasses(r.moduleName(), subclasses) && areAccessible(module, name, subclasses)) {
                    // All subclasses are local, so we can mark this class sealed
                    if (markSealed) {
                        cn.permittedSubclasses = new ArrayList<>(subclasses);
                        log(DEBUG, "Sealing %s to %s", name, subclasses);
                        modified = true;
                        stats.localSubclassesOnly++;
                    }
                }
            }
        }
        if (modified) {
            ClassWriter cw = new ClassWriter(cr, 0);
            cn.accept(cw);
            byte[] outBytes = cw.toByteArray();
            r = r.copyWithContent(outBytes);
        }
        stats.total++;
        return r;
    }

    private boolean areAccessible(ResourcePoolModule module, String className, Collection<String> subclasses) {
        return subclasses.stream().allMatch(snm -> isAccessible(module, className, snm));
    }

    // Naive implementation that checks if the target class is accessible from the source class
    // (both classes must be in the same module)
    private boolean isAccessible(ResourcePoolModule module, String sourceClassName, String targetClassName) {
        if (targetClassName.contains("$")) {
            // A hack that filters out some unwanted cases
            return false;
        }
        if (!getPackage(sourceClassName).equals(getPackage(targetClassName))) {
            // If they're from different packages we check if the target is public
            String path = "/" + module.name() + "/" + targetClassName + ".class";
            Optional<ResourcePoolEntry> entry = module.findEntry(path);
            if (entry.isPresent()) {
                ClassReader cr = newClassReader(path, entry.get());
                return (cr.getAccess() & Opcodes.ACC_PUBLIC) != 0;
            } else {
                log(WARNING, "Entry %s not found in module %s", targetClassName, module.name());
                return false;
            }
        } else {
            // If they're from the same package we assume src can access target
            // TODO this doesn't handle inner classes etc at all!
            return true;
        }
    }

    private static String getPackage(String internalClassName) {
        int index = internalClassName.lastIndexOf("/");
        return index == -1 ? "" : internalClassName.substring(0, index);
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

    private static void log(System.Logger.Level level, String fmt, Object... args) {
        if (isLoggable(level)) {
            System.err.printf("SealPlugin: %s: ", level.name());
            System.err.printf(fmt + "\n", args);
        }
    }

    private static boolean isLoggable(System.Logger.Level level) {
        return level != System.Logger.Level.OFF
                && level.ordinal() >= logLevel.ordinal();
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
        public int withSubclasses;
        public int notFinal;
        public int notSealed;
        public int localSubclassesOnly;

        public void print() {
            log(INFO, "#Classes/interfaces found: %d", total);
            log(INFO, "#Classes without subclasses: %d", noSubclasses);
            log(INFO, "#Classes not already final: %d", notFinal);
            log(INFO, "#Classes/interfaces with subclasses: %d", withSubclasses);
            log(INFO, "#Classes/interfaces not already sealed: %d", notSealed);
            log(INFO, "#Classes/interfaces with only local subclasses: %d", localSubclassesOnly);
        }
    }
}
