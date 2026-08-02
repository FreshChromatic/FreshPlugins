package github.freshchromatic.freshlib.util;

public final class Folia {
    public static final boolean FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (final ClassNotFoundException e) {
            folia = false;
        }
        FOLIA = folia;
    }

    private Folia() {
    }
}
