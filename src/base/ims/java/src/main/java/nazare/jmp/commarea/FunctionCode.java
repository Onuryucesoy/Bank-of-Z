package nazare.jmp.commarea;

/** {@code PROCISRT-FUNCTION PIC X} and its 88-level values from {@code src/base/cics/copy/PROCISRT.cpy}. */
public enum FunctionCode {
    DEBIT('1'),
    CREDIT('2'),
    XFR_LOCAL('3'),
    DELETE_CUSTOMER('4'),
    CREATE_CUSTOMER('5'),
    DELETE_ACCOUNT('6'),
    CREATE_ACCOUNT('7');

    private final char code;

    FunctionCode(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    public static FunctionCode fromCode(char code) {
        for (FunctionCode f : values()) {
            if (f.code == code) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown PROCISRT-FUNCTION '" + code + "'");
    }
}
