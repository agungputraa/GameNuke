package go;

public abstract class Universe {
    private static native void _init();

    public static void touch() {
    }

    static {
        Seq.touch();
        _init();
    }

    private Universe() {
    }

    static final class proxyerror extends Exception implements Seq.Proxy, error {
        private final int refnum;

        @Override
        public native String error();

        @Override
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyerror(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override
        public String getMessage() {
            return error();
        }
    }
}
