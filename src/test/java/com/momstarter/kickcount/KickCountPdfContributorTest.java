package com.momstarter.kickcount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KickCountPdfContributor} — the K-7 PDF note gate logic.
 *
 * <p>Covers (compliance K-7 / api-contract "Note-decryption gate" / functional spec §A.4 D9):
 * <ul>
 *   <li><strong>includeLab=true</strong> — {@code note_cipher} bytes included in PDF row under
 *       the {@code sensitive_lab_results} gate; no carve-out (same rule as every other note_cipher).</li>
 *   <li><strong>includeLab=false</strong> — note is OMITTED; a redaction marker is emitted instead.
 *       The PDF is still produced — this is NEVER a 403 (functional spec §A.4 D9).</li>
 *   <li>Descriptive values ({@code movementCount}, {@code durationSeconds}, {@code targetCount},
 *       {@code gestationalWeekAtStart}, {@code startedAt}/{@code endedAt}) render under
 *       {@code general_health} regardless of {@code includeLab} — they are NOT sensitive.</li>
 *   <li>Server NEVER adds verdict/clinical summary fields (INV-K1 — no verdict in PDF).</li>
 *   <li>Sessions with {@code null} note (no note entered) emit no note field and no redaction
 *       marker (there is nothing to redact).</li>
 * </ul>
 */
class KickCountPdfContributorTest {

    private final KickCountPdfContributor contributor = new KickCountPdfContributor();

    // -------------------------------------------------------------------------
    // includeLab=true → note_cipher bytes included
    // -------------------------------------------------------------------------

    @Test
    void buildPdfRow_includeLabTrue_noteIncluded() {
        // K-7: note_cipher under sensitive_lab_results gate → included when includeLab=true
        byte[] cipher = new byte[]{0x10, 0x20, 0x30};
        KickCountSession session = session(cipher, 10, 900, 36);

        Map<String, Object> row = contributor.buildPdfRow(session, /* includeLab= */ true);

        assertThat(row).containsKey("note");
        assertThat(row.get("note")).isEqualTo(Base64.getEncoder().encodeToString(cipher));
        // No redaction marker when includeLab=true
        assertThat(row).doesNotContainKey("noteRedacted");
    }

    // -------------------------------------------------------------------------
    // includeLab=false → note omitted, redaction marker emitted
    // -------------------------------------------------------------------------

    @Test
    void buildPdfRow_includeLabFalse_noteOmitted_redactionMarkerPresent() {
        // K-7: omit note when includeLab=false; render redaction marker; PDF still produced (no 403)
        byte[] cipher = new byte[]{0x10, 0x20, 0x30};
        KickCountSession session = session(cipher, 10, 900, 36);

        Map<String, Object> row = contributor.buildPdfRow(session, /* includeLab= */ false);

        assertThat(row).doesNotContainKey("note");
        assertThat(row.get("noteRedacted")).isEqualTo(Boolean.TRUE);
    }

    // -------------------------------------------------------------------------
    // Descriptive values always included (not gated by includeLab)
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void buildPdfRow_descriptiveValues_alwaysIncluded(boolean includeLab) {
        // Descriptive values render under general_health regardless of includeLab (D9 / functional §A.4)
        KickCountSession session = session(new byte[]{0x01}, 7, 4242, 34);
        session.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        session.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 15));
        session.setTargetCount(10);

        Map<String, Object> row = contributor.buildPdfRow(session, includeLab);

        assertThat(row).containsKey("movementCount");
        assertThat(row).containsKey("durationSeconds");
        assertThat(row).containsKey("targetCount");
        assertThat(row).containsKey("gestationalWeekAtStart");
        assertThat(row).containsKey("startedAt");
        assertThat(row).containsKey("endedAt");
        assertThat(row.get("movementCount")).isEqualTo(7);
        assertThat(row.get("durationSeconds")).isEqualTo(4242);
        assertThat(row.get("gestationalWeekAtStart")).isEqualTo(34);
    }

    // -------------------------------------------------------------------------
    // No verdict / clinical summary fields (INV-K1)
    // -------------------------------------------------------------------------

    @Test
    void buildPdfRow_noVerdictField_INV_K1() {
        // INV-K1: server NEVER adds verdict/clinical interpretation to the PDF
        KickCountSession session = session(null, 3, 300, 33); // count=3, below target
        Map<String, Object> row = contributor.buildPdfRow(session, true);
        // Must NOT have any interpretation fields
        assertThat(row).doesNotContainKeys("verdict", "isNormal", "assessment",
                "tooFew", "reachedTarget", "belowThreshold", "summary");
    }

    // -------------------------------------------------------------------------
    // Session with no note — no redaction marker either
    // -------------------------------------------------------------------------

    @Test
    void buildPdfRow_nullNote_noNoteAndNoRedaction() {
        // No note entered → no note field, no redaction marker (nothing to redact)
        KickCountSession session = session(/* noteCipher= */ null, 10, 900, 35);

        Map<String, Object> rowInclude = contributor.buildPdfRow(session, true);
        Map<String, Object> rowExclude = contributor.buildPdfRow(session, false);

        assertThat(rowInclude).doesNotContainKey("note");
        assertThat(rowInclude).doesNotContainKey("noteRedacted");

        assertThat(rowExclude).doesNotContainKey("note");
        assertThat(rowExclude).doesNotContainKey("noteRedacted");
    }

    // -------------------------------------------------------------------------
    // buildPdfRows helper — processes multiple sessions, filters tombstoned
    // -------------------------------------------------------------------------

    @Test
    void buildPdfRows_filtersOutTombstoned() {
        KickCountSession live = session(null, 10, 900, 35);
        KickCountSession tombstoned = session(null, 5, 400, 33);
        tombstoned.setDeletedAt(java.time.Instant.now());

        List<Map<String, Object>> rows = contributor.buildPdfRows(
                List.of(live, tombstoned), /* includeLab= */ true);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("movementCount")).isEqualTo(10);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private KickCountSession session(byte[] noteCipher, int movementCount,
                                      int durationSeconds, int gestWeek) {
        KickCountSession s = new KickCountSession();
        s.setId(UUID.randomUUID());
        s.setUserId(UUID.randomUUID());
        s.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 15));
        s.setMovementCount(movementCount);
        s.setDurationSeconds(durationSeconds);
        s.setTargetCount(10);
        s.setStatus("completed");
        s.setGestationalWeekAtStart(gestWeek);
        s.setNoteCipher(noteCipher);
        return s;
    }
}
