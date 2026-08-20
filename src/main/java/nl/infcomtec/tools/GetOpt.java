package nl.infcomtec.tools;

import java.io.FileOutputStream;
import java.io.PrintStream;

/**
 * This class supports parsing command line options. It is a simplified,
 * hand-rolled parser loosely inspired by GNU getopt_long: short options
 * (one letter, e.g. {@code -D}) and long options (e.g. {@code --debug} or
 * {@code --config-file=FILE}) are both supported, and {@code --} ends
 * option parsing (POSIX-style). It is NOT a full POSIX getopt or GNU
 * getopt_long implementation: short options cannot be bundled (no
 * {@code -abc}) and a short option's argument must be a separate token
 * (no {@code -cVALUE}).
 *
 * <p>
 * The following are the standard options:
 *
 * <dl>
 * <dt>
 * -D --debug
 * </dt>
 * <dd>
 * Run in "debug" mode. The exact result of this is application dependent but at
 * least no IO-redirection should be done.<br>
 * </dd>
 * <dt>
 * -c --config-file=FILE
 * </dt>
 * <dd>
 * Use FILE as the configuration.
 * </dd>
 * <dt>
 * -s --select-key=KEY
 * </dt>
 * <dd>
 * Use KEY as a prefix in the configuration. The idea is that configurations can
 * be "pooled" together in one big file (or not). To enable this, all options
 * must be distinct from any other program's options. In a JAVA property file
 * this would be a fixed prefix for each property.
 * </dd>
 * <dt>
 * -o --stdout=FILE, -e --stderr=FILE
 * </dt>
 * <dd>
 * As these options are for "Daemons", i.e. a program which runs in the
 * background, it is obvious we will have to leave our output somewhere else
 * than on a non-existing console. This is controlled by these two options.
 * Likewise, the input stream is closed as a background process can not be
 * reading from input and should always get EOF if it attempts to do so.
 * </dd>
 * <dt>
 * -V --version, -? --help
 * </dt>
 * <dd>
 * These are standard options which every program should implement.
 * </dd>
 * </dl>
 * </p>
 *
 * @version $Id: GetOpt.java,v 1.1.1.1 2006/08/29 09:23:56 walter Exp $
 */
public class GetOpt {
    //~ Instance variables =========================================================================

    /**
     * After the command line has been parsed, any non-option arguments will
     * have been placed in this String array. Otherwise this member will remain
     * null.
     */
    public String[] args = null;

    /**
     * The standard options.
     */
    public Option[] opts = new Option[]{
        new Option('D', "debug", null, "Start the process in debugmode.", null),
        new Option('c', "config-file", "FILE", "Read the configuration from FILE.", null),
        new Option((char) 0, "select-key", "KEY", "Use KEY as a prefix in the configuration FILE.", null),
        new Option((char) 0, "stdout", "FILE", "Use FILE as standard output.", "/dev/null"),
        new Option((char) 0, "stderr", "FILE", "Use FILE as standard error.", "/dev/null"),
        new Option('V', "version", null, "Show the version of this process.", null),
        new Option('?', "help", null, "Show the command line usage.", null)
    };

    /**
     * This flag signals that the process is to be run in "debug" mode. This
     * class will skip the IO-redirection if this option was used.
     */
    public boolean debugMode = false;

    //~ Constructors ===============================================================================
    /**
     * Parses the command line as passed and creates a new instance of GetOpt.
     *
     * @param commandLine The "args" parameter of the main() method.
     * @param version A string identifying the (build) version of this program.
     * @param options Any additional options this program accepts.
     * @param moreUsage Any additional text to display if the user asks for
     * help. One line per array element.
     */
    public GetOpt(String[] commandLine, String version, Option[] options, String[] moreUsage) {
        if (options != null) {
            Option[] std = opts;
            Option[] cmb = new Option[std.length + options.length];
            int dst = 0;

            for (Option std1 : std) {
                cmb[dst++] = std1;
            }
            for (Option option : options) {
                for (int j = 0; j < dst; j++) {
                    if ((cmb[j].shortOption != 0 && cmb[j].shortOption == option.shortOption) || cmb[j].longOption.equals(option.longOption)) {
                        System.err.println("Duplicate option definition: " + cmb[j]);
                        System.err.println("Conflicts with: " + option);
                        System.exit(1);
                    }
                }
                cmb[dst++] = option;
            }
            opts = cmb;
        }
        parseCommandLine(commandLine, version, moreUsage);
    }

    //~ Methods ====================================================================================
    /**
     * Get the filled-in Option object for the option.
     *
     * @param longOptionName The name of the option to return.
     *
     * @return The filled-in Option object or null if the option was not given
     * by the user and did not have a default value.
     */
    public Option getOption(String longOptionName) {
        for (Option opt : opts) {
            if (opt.longOption.equals(longOptionName)) {
                return opt.isSet() ? opt : null;
            }
        }
        System.err.println("No such option: '" + longOptionName + "'");
        System.exit(2);
        return null; // unreachable code
    }

    /**
     * Get the value of the option as a String.
     *
     * @param longOptionName The option to retrieve.
     *
     * @return An empty string if not set, otherwise the value of the option.
     * Use getOption() to determine if an option was set or not.
     */
    public String getValueForOption(String longOptionName) {
        Option opt = getOption(longOptionName);

        if (opt != null) {
            return opt.value;
        }
        return new String();
    }

    /**
     * IO redirection support.
     *
     */
    public void doRedirection() {
        if (debugMode) {
            return;
        }
        PrintStream keepThis = System.out;

        try {
            System.in.close();
            String stderr = getValueForOption("stderr");
            String stdout = getValueForOption("stdout");

            System.setErr(new PrintStream(new FileOutputStream(stderr, true)));
            System.setOut(new PrintStream(new FileOutputStream(stdout, true)));
        } catch (Exception ex) {
            ex.printStackTrace(keepThis);
            System.exit(3);
        }
    }

    /**
     * For debug purposes.
     *
     * @param out Destination.
     */
    public void printParseResult(PrintStream out) {
        out.println("ParseResult: debugMode=" + debugMode);
        if (args == null) {
            out.println("(No non-option arguments)");
        } else {
            out.print("Remaining arguments=");
            for (int i = 0; i < args.length; i++) {
                out.print(((i == 0) ? "" : " ") + args[i]);
            }
            out.println();
        }
        out.println("Options:");
        for (Option opt : opts) {
            out.println("" + opt);
        }
    }

    /**
     * Called from the constructor
     */
    private void parseCommandLine(String[] commandLine, String version, String[] moreUsage) {
        int optIdx = -1;

        for (int a = 0; a < commandLine.length; a++) {
            if (optIdx != -1) {
                opts[optIdx].value = commandLine[a];
                optIdx = -1;
            } else {
                if (commandLine[a].charAt(0) == '-') {
                    if ((commandLine[a].length() > 2) && (commandLine[a].charAt(1) == '-')) {
                        // long option
                        String thisOpt = commandLine[a].substring(2);
                        int eqPos = thisOpt.indexOf('=');

                        if (eqPos > 0) {
                            commandLine[a] = thisOpt.substring(eqPos + 1);
                            a--;
                            thisOpt = thisOpt.substring(0, eqPos);
                        }
                        for (optIdx = 0; optIdx < opts.length; optIdx++) {
                            if (opts[optIdx].longOption.equals(thisOpt)) {
                                break;
                            }
                        }
                    } else if ((commandLine[a].length() == 2)
                            && (commandLine[a].charAt(1) != '-')) {
                        // short option
                        for (optIdx = 0; optIdx < opts.length; optIdx++) {
                            if (opts[optIdx].shortOption == commandLine[a].charAt(1)) {
                                break;
                            }
                        }
                    } else if ((commandLine[a].length() == 2)
                            && (commandLine[a].charAt(1) == '-')) {
                        // the remaining strings are arguments
                        a++;
                        int left = commandLine.length - a;

                        if (left > 0) {
                            int dst = 0;

                            if (args != null) {
                                String[] newArgs = new String[args.length + left];
                                System.arraycopy(args, 0, newArgs, 0, args.length);
                                args = newArgs;
                                dst = args.length;
                            } else {
                                args = new String[left];
                            }
                            while (a < commandLine.length) {
                                args[dst++] = commandLine[a++];
                            }
                        }
                        break; // from commandLine iteration
                    } else {
                        System.err.println("Option syntax error: '" + commandLine[a] + "'");
                    }

                    // Special case: --help or error
                    if ((optIdx < 0) || (optIdx >= opts.length)
                            || opts[optIdx].longOption.equals("help")) {
                        showUsage(moreUsage);
                        System.exit(0);
                    }

                    // Special case: -V or --version
                    if ((optIdx >= 0) && (optIdx < opts.length)
                            && opts[optIdx].longOption.equals("version")) {
                        System.out.println(version);
                        System.exit(0);
                    }
                    if (optIdx == opts.length) {
                        System.err.println("Unknown option: '" + commandLine[a] + "'");
                        System.exit(1);
                    } else if (opts[optIdx].isSet()) {
                        System.err.println("Option: '" + commandLine[a]
                                + "' is specified more than once");
                        System.err.println("Was: " + opts[optIdx]);
                        System.exit(1);
                    } else if (opts[optIdx].optionArgument == null) {
                        // option has no arguments
                        opts[optIdx].value = new String();
                        optIdx = -1;
                    }
                    // else we need an argument
                } else {
                    // argument
                    if (args != null) {
                        String[] newArgs = new String[args.length + 1];
                        int i;

                        for (i = 0; i < args.length; i++) {
                            newArgs[i] = args[i];
                        }
                        newArgs[i] = commandLine[a];
                        args = newArgs;
                    } else {
                        args = new String[]{commandLine[a]};
                    }
                }
            }
        }
        if (optIdx != -1) {
            System.err.println("Option " + opts[optIdx]
                    + " requires an argument but none was given");
            System.exit(1);
        }

        // Fill in defaults and check for debugMode
        for (Option opt : opts) {
            if (!opt.isSet() && (opt.defaultValue != null)) {
                opt.value = opt.defaultValue;
            }
            if (opt.shortOption == 'D') {
                debugMode = opt.isSet();
            }
        }
    }

    /**
     * On --help or if an error was detected
     *
     * @param moreUsage Extra information.
     */
    private void showUsage(String[] moreUsage) {
        for (Option opt : opts) {
            opt.printUsage();
        }
        if (moreUsage != null) {
            System.out.println();
            for (String more : moreUsage) {
                System.out.println(more);
            }
        }
    }
}
