package com.relayaegis.remediation;

import java.util.ArrayList;
import java.util.List;

/** Minimal line-based unified diff (LCS over the changed window) for reviewing proposed patches. */
public final class UnifiedDiff {
    private static final long MAX_LCS_CELLS = 4_000_000L;

    private UnifiedDiff() {}

    enum Kind { EQUAL, DELETE, INSERT }

    /** aPos/bPos are 0-based positions in the old/new line lists where this line sits (or would be inserted). */
    record Op(Kind kind, int aPos, int bPos, String text) {}

    public record Result(String patch, List<String> additions, List<String> removals) {}

    public static Result compute(String path, List<String> oldLines, List<String> newLines, int context) {
        List<Op> ops = ops(oldLines, newLines);
        List<String> additions = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        for (Op op : ops) {
            if (op.kind() == Kind.INSERT) additions.add(op.text());
            if (op.kind() == Kind.DELETE) removals.add(op.text());
        }
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n');
        int index = 0;
        while (index < ops.size()) {
            int firstChange = nextChange(ops, index);
            if (firstChange < 0) break;
            int start = Math.max(index, firstChange - context);
            int lastChange = firstChange;
            int probe = nextChange(ops, firstChange + 1);
            while (probe >= 0 && probe - lastChange <= 2 * context) { lastChange = probe; probe = nextChange(ops, probe + 1); }
            int end = Math.min(ops.size() - 1, lastChange + context);
            int oldCount = 0, newCount = 0;
            for (int i = start; i <= end; i++) {
                if (ops.get(i).kind() != Kind.INSERT) oldCount++;
                if (ops.get(i).kind() != Kind.DELETE) newCount++;
            }
            Op first = ops.get(start);
            int oldStart = oldCount == 0 ? first.aPos() : first.aPos() + 1;
            int newStart = newCount == 0 ? first.bPos() : first.bPos() + 1;
            out.append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            for (int i = start; i <= end; i++) {
                Op op = ops.get(i);
                out.append(op.kind() == Kind.EQUAL ? ' ' : op.kind() == Kind.DELETE ? '-' : '+').append(op.text()).append('\n');
            }
            index = end + 1;
        }
        return new Result(out.toString(), additions, removals);
    }

    private static int nextChange(List<Op> ops, int from) {
        for (int i = from; i < ops.size(); i++) if (ops.get(i).kind() != Kind.EQUAL) return i;
        return -1;
    }

    static List<Op> ops(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) ops.add(new Op(Kind.EQUAL, i, i, a.get(i)));
        List<String> wa = a.subList(prefix, a.size() - suffix);
        List<String> wb = b.subList(prefix, b.size() - suffix);
        if ((long) (wa.size() + 1) * (wb.size() + 1) > MAX_LCS_CELLS) {
            // Too large for an exact LCS: show the window as a replacement rather than failing.
            for (int i = 0; i < wa.size(); i++) ops.add(new Op(Kind.DELETE, prefix + i, prefix, wa.get(i)));
            for (int j = 0; j < wb.size(); j++) ops.add(new Op(Kind.INSERT, prefix + wa.size(), prefix + j, wb.get(j)));
        } else {
            int[][] lcs = new int[wa.size() + 1][wb.size() + 1];
            for (int i = wa.size() - 1; i >= 0; i--)
                for (int j = wb.size() - 1; j >= 0; j--)
                    lcs[i][j] = wa.get(i).equals(wb.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            int i = 0, j = 0;
            while (i < wa.size() || j < wb.size()) {
                if (i < wa.size() && j < wb.size() && wa.get(i).equals(wb.get(j))) { ops.add(new Op(Kind.EQUAL, prefix + i, prefix + j, wa.get(i))); i++; j++; }
                // On ties prefer deletions, so a replaced line reads "-old" then "+new" as reviewers expect.
                else if (i < wa.size() && (j == wb.size() || lcs[i + 1][j] >= lcs[i][j + 1])) { ops.add(new Op(Kind.DELETE, prefix + i, prefix + j, wa.get(i))); i++; }
                else { ops.add(new Op(Kind.INSERT, prefix + i, prefix + j, wb.get(j))); j++; }
            }
        }
        for (int k = 0; k < suffix; k++) {
            int ai = a.size() - suffix + k, bi = b.size() - suffix + k;
            ops.add(new Op(Kind.EQUAL, ai, bi, a.get(ai)));
        }
        return ops;
    }
}
