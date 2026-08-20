package nl.infcomtec.tools;

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * The individual options.
 */
public class Option {
    //~ Instance variables =========================================================================

    /**
     * The default value for options that take an argument.
     */
    public String defaultValue = null;

    /**
     * The long option (a word or words joined by minus signs).
     */
    public String longOption = null;

    /**
     * If null the option does not take an argument. Otherwise this should be
     * the word used in the usage string. For instance, optionArgument="FILE",
     * usage="Use FILE as the configuration file".
     */
    public String optionArgument = null;

    /**
     * The usage string to display in printUsage().
     */
    public String usage = null;

    /**
     * The value (or defaultValue) of the option after the parsing has been
     * done. Note that options without arguments do have a value (an empty
     * string) if they were specified.
     */
    public String value = null;

    /**
     * The short option (a single letter).
     */
    public char shortOption = 0;

    /**
     * The constructor.
     *
     * @param so short option, one character. May be 0 (zero) is the option does
     * not have a short equivalent
     * @param lo long option
     * @param oa option argument (name) or null for options without arguments.
     * @param us option usage. Must refer to the option argument name if there
     * is one.
     * @param df default value for options that have arguments
     */
    public Option(char so, String lo, String oa, String us, String df) {
        shortOption = so;
        longOption = lo;
        optionArgument = oa;
        usage = us;
        defaultValue = df;
    }

    /**
     *
     * @return True if the option was set or has a default value.
     */
    public boolean isSet() {
        return value != null;
    }

    /**
     * Shows the help for this option.
     */
    public void printUsage() {
        if (shortOption != 0) {
            System.out.print("-" + shortOption);
        } else {
            System.out.print("  ");
        }
        System.out.print(" --" + longOption);
        if (optionArgument != null) {
            System.out.print("=" + optionArgument);
        }
        System.out.print(" " + usage);
        if (defaultValue != null) {
            System.out.println(" (default=" + defaultValue + ")");
        } else {
            System.out.println();
        }
    }

    /**
     *
     * @return Debug dump of this option.
     */
    @Override
    public String toString() {
        HashMap<Object, Object> aHashMap = new HashMap<>();
        Class aClass = getClass();
        Field[] fields = aClass.getDeclaredFields();

        try {
            java.lang.reflect.AccessibleObject.setAccessible(fields, true);
            for (Field field : fields) {
                aHashMap.put(field.getName(), field.get(this));
            }
        } catch (Exception e) {
            // ignored!
        }
        if (aClass.getSuperclass() != Object.class) {
            aHashMap.put("Super", super.toString());
        }
        return aClass.getName() + aHashMap;
    }
}
