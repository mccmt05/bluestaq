package com.bluestaq.notes.note;

import java.util.List;

/**
 * Returns one offset-based page of notes together with the total from the same database snapshot.
 * Notes are ordered by descending update time and then descending ID to break ties.
 *
 * @param items notes in this page, possibly empty even when earlier pages contain notes
 * @param page zero-based page number requested by the client
 * @param size maximum number of notes requested per page, between 1 and 100 at the HTTP boundary
 * @param totalElements total number of notes in the requested team
 */
public record NotePage(List<Note> items, int page, int size, long totalElements) {
}
