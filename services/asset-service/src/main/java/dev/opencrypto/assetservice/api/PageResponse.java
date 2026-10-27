package dev.opencrypto.assetservice.api;

import java.util.List;

/** Standard paginated response envelope (offset pagination; fine for a catalogue of this size). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, String sort) {
 public static <T> PageResponse<T> of(List<T> items, int page, int size, long total, String sort) {
  return new PageResponse<>(items, page, size, total, (int) Math.ceil(total / (double) size), sort);
 }
}
