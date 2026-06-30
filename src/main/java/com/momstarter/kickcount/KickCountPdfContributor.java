package com.momstarter.kickcount;

import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds PDF row data for {@link KickCountSession} records within the
 * {@code POST /reports} (doctor PDF) egress path.
 *
 * <h3>K-7 note gate (compliance K-7 / api-contract "Note-decryption gate" / D9)</h3>
 * <p>The {@code kick_count_session.note_cipher} field rides the standard
 * {@code sensitive_lab_results} gate like every other {@code note_cipher} column.
 * The gate name denotes the <em>disclosure level</em> for never-parsed free-text
 * (which may contain SD-7 sensitive values), NOT a classification of the note as a lab result.
 * (compliance rev2 §2.4/K-7 — confirmed, no kick-count-specific carve-out.)
 *
 * <p>PDF service MUST invoke {@link #buildPdfRow} with {@code includeLab = true/false}
 * derived from the report request's {@code includeLab} parameter:
 * <ul>
 *   <li>{@code includeLab=true} → note_cipher bytes are included (Base64-encoded).</li>
 *   <li>{@code includeLab=false} → note is OMITTED; a {@code noteRedacted=true} marker is
 *       emitted in its place. The PDF is STILL PRODUCED — this is never a {@code 403}.</li>
 * </ul>
 *
 * <h3>Descriptive values (not gated by includeLab)</h3>
 * <p>{@code movementCount}, {@code durationSeconds}, {@code targetCount},
 * {@code gestationalWeekAtStart}, {@code startedAt}/{@code endedAt} are included under
 * {@code general_health} regardless of {@code includeLab} (functional spec §A.4 / D9).
 *
 * <h3>No verdict (INV-K1)</h3>
 * <p>Server NEVER adds interpretation, verdict, or clinical summary fields to the PDF.
 * The PDF service echoes descriptive values only; clinical judgment belongs to the doctor.
 */
@Component
public class KickCountPdfContributor {

    /** Floating-civil formatter for startedAt / endedAt (FLAG-1). */
    private static final DateTimeFormatter CIVIL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * Builds one PDF row for a single {@link KickCountSession}.
     *
     * @param session    the completed session (must be live — tombstoned rows should be filtered
     *                   by the caller via {@link #buildPdfRows})
     * @param includeLab whether the {@code sensitive_lab_results} gate is open (K-7)
     * @return serialisable map of field name → value for PDF rendering
     */
    public Map<String, Object> buildPdfRow(KickCountSession session, boolean includeLab) {
        Map<String, Object> row = new LinkedHashMap<>();

        // --- Descriptive values — always included under general_health ---
        row.put("startedAt",
                session.getStartedAt() != null ? session.getStartedAt().format(CIVIL_FMT) : null);
        row.put("endedAt",
                session.getEndedAt() != null ? session.getEndedAt().format(CIVIL_FMT) : null);
        row.put("movementCount", session.getMovementCount());
        row.put("durationSeconds", session.getDurationSeconds());
        row.put("targetCount", session.getTargetCount());
        row.put("gestationalWeekAtStart", session.getGestationalWeekAtStart());

        // --- K-7 note gate (sensitive_lab_results) ---
        byte[] cipher = session.getNoteCipher();
        if (cipher != null) {
            if (includeLab) {
                // Gate open: include the client-encrypted ciphertext (client decrypts)
                row.put("note", Base64.getEncoder().encodeToString(cipher));
            } else {
                // Gate closed: omit note, emit redaction marker
                // PDF layout should render: "results hidden (sensitive results not consented for inclusion)"
                row.put("noteRedacted", Boolean.TRUE);
            }
        }
        // null cipher (no note entered): neither key is emitted — nothing to redact

        // INV-K1: DO NOT add verdict, assessment, clinical-summary, or threshold fields here.
        // Descriptive echo only; clinical judgment is the doctor's responsibility.

        return row;
    }

    /**
     * Convenience method to build PDF rows for a list of sessions.
     *
     * <p>Tombstoned sessions ({@code deletedAt != null}) are silently excluded —
     * they have no surviving data to render (crypto-shredded on tombstone).
     *
     * @param sessions   list of {@link KickCountSession} entities (live and tombstoned mix allowed)
     * @param includeLab whether the {@code sensitive_lab_results} gate is open
     * @return list of row maps in input order (tombstones excluded)
     */
    public List<Map<String, Object>> buildPdfRows(List<KickCountSession> sessions,
                                                   boolean includeLab) {
        return sessions.stream()
                .filter(s -> s.getDeletedAt() == null)
                .map(s -> buildPdfRow(s, includeLab))
                .collect(Collectors.toList());
    }
}
