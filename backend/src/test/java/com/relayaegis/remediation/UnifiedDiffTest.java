package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {
    @Test
    void producesASingleHunkWithContextForOneChangedLine() {
        List<String> before = List.of("a", "b", "c", "d", "e", "f", "g");
        List<String> after = List.of("a", "b", "c", "D", "e", "f", "g");

        UnifiedDiff.Result result = UnifiedDiff.compute("src/X.java", before, after, 2);

        assertEquals("""
                --- a/src/X.java
                +++ b/src/X.java
                @@ -2,5 +2,5 @@
                 b
                 c
                -d
                +D
                 e
                 f
                """, result.patch());
        assertEquals(List.of("D"), result.additions());
        assertEquals(List.of("d"), result.removals());
    }

    @Test
    void splitsDistantChangesIntoSeparateHunksAndCountsInsertions() {
        List<String> before = new ArrayList<>();
        for (int i = 1; i <= 30; i++) before.add("line " + i);
        List<String> after = new ArrayList<>(before);
        after.add(0, "import os");
        after.set(25, "changed 25");

        String patch = UnifiedDiff.compute("m.py", before, after, 3).patch();

        assertTrue(patch.contains("@@ -1,3 +1,4 @@\n+import os\n line 1\n"), patch);
        assertTrue(patch.contains("-line 25\n+changed 25\n"), patch);
        assertEquals(2, patch.split("@@ -", -1).length - 1, patch);
    }

    @Test
    void identicalInputHasNoHunks() {
        String patch = UnifiedDiff.compute("f.txt", List.of("x", "y"), List.of("x", "y"), 3).patch();
        assertEquals("--- a/f.txt\n+++ b/f.txt\n", patch);
    }
}
