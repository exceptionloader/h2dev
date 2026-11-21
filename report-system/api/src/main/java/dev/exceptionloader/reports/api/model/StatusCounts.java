package dev.exceptionloader.reports.api.model;

/**
 * Number of reports per status.
 */
public record StatusCounts(long open, long resolved, long rejected) {

    public static final StatusCounts EMPTY = new StatusCounts(0, 0, 0);

    public long total() {
        return open + resolved + rejected;
    }

    public long closed() {
        return resolved + rejected;
    }

    /**
     * Share of closed reports that were confirmed.
     *
     * @return value between {@code 0} and {@code 1}, {@code 0} if nothing was decided yet
     */
    public double acceptanceRate() {
        long closed = closed();
        return closed == 0 ? 0 : (double) resolved / closed;
    }
}
