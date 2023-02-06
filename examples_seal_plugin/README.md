# Seal plugin

The seal plugin is a plugin for the `jlink` command that analyses
classes and tries to determine if the can be marked `final` or `sealed`.

It does this by scanning all classes that are available and it then
creates a hierarchy of classes and their subclasses. Any classes that
have no subclasses are marked `final`, while any classes that only
have subclasses that are part of the same module are marked `sealed`.

The plugin can be activated by simply passing the "--seal" flag to the
`jlink` command. The flag takes arguments:

    --seal=<module_names>:final=[y|n]:sealed=[y|n]:excludefile=<path>:log=<level>

The flag has a single, required, argument which is a comma-separated
list of module names that will be analysed and processed. This can be
set to `*` to process all modules.

All the other arguments are optional:

 - final - takes `y` or `n` to indicate that marking with `final` should
            or should not be taking place. Default `y`.
 - sealed - takes `y` or `n` to indicate that marking with `sealed` should
            or should not be taking place. Default `y`.
 - excludefile - takes a path to a text file with class names that should
            be excluded from `sealed` processing. If the name is that of an
            outer class all its inner classes will also be excluded.
 - log - sets the log output level for the plugin. Takes either `error`,
            `warning`, `info`, `debug` or `trace`. Default `warning`.

## Example

This folder contains some scripts and example code to make
testing the `SealPlugin` a bit easier.

First you need to compile the JDK by following the instructions
in [doc/building.md], although it basically boils down to running

    $ make images

in the root of the project.

Then you can compile the small test app by running:

    $ ./examples_seal_plugin/compile.sh

And then we need to create our special "distro" just for our app.

    $ ./examples_seal_plugin/jlink.sh

And to run our app:

    $ ./examples_seal_plugin/customjre/bin/customjrelauncher

