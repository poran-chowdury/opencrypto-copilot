package dev.opencrypto.swapservice.api;

import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.platform.web.DemoUser;
import dev.opencrypto.swapservice.domain.SwapModels.Quote;
import dev.opencrypto.swapservice.domain.SwapModels.SwapOrder;
import dev.opencrypto.swapservice.domain.SwapRepository;
import dev.opencrypto.swapservice.domain.SwapService;

@RestController
@RequestMapping("/api/v1/swaps")
@Validated
public class SwapController {
 public record QuoteRequest(@NotBlank String fromSymbol, @NotBlank String toSymbol,
   @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000000") BigDecimal amount,
   @Min(1) @Max(500) Integer slippageBps) {}
 public record ExecuteRequest(@NotNull UUID quoteId) {}
 public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}

 private final SwapService swaps;
 private final SwapRepository repo;

 public SwapController(SwapService swaps, SwapRepository repo) { this.swaps = swaps; this.repo = repo; }

 /** Price a swap. Nothing moves until the quote is executed; quotes expire after 30 seconds. */
 @PostMapping("/quotes")
 public Quote quote(@RequestHeader(value = DemoUser.HEADER, required = false) String user, @Valid @RequestBody QuoteRequest r) {
  return swaps.quote(DemoUser.resolve(user), r.fromSymbol().toUpperCase(Locale.ROOT), r.toSymbol().toUpperCase(Locale.ROOT), r.amount(),
   r.slippageBps() == null ? 100 : r.slippageBps());
 }

 /**
  * Execute a quote. Returns 202 Accepted: the swap continues asynchronously (poll GET /swaps/{id}).
  * The same Idempotency-Key always returns the same order (200), so retries are safe.
  */
 @PostMapping
 public ResponseEntity<SwapOrder> execute(@RequestHeader(value = DemoUser.HEADER, required = false) String user,
   @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody ExecuteRequest r) {
  if (!idempotencyKey.matches("[A-Za-z0-9_-]{8,64}"))
   throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-64 characters of letters, digits, '_' or '-'");
  SwapService.ExecuteResult result = swaps.execute(DemoUser.resolve(user), r.quoteId(), idempotencyKey);
  return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
   .header("Idempotent-Replayed", String.valueOf(!result.created()))
   .header("Location", "/api/v1/swaps/" + result.order().orderId())
   .body(result.order());
 }

 @GetMapping
 public PageResponse<SwapOrder> list(@RequestHeader(value = DemoUser.HEADER, required = false) String user,
   @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
   @RequestParam(required = false) String state) {
  String u = DemoUser.resolve(user);
  String s = state == null || state.isBlank() ? null : state.toUpperCase(Locale.ROOT);
  long total = repo.count(u, s);
  return new PageResponse<>(repo.page(u, s, size, page * size), page, size, total, (int) Math.ceil(total / (double) size));
 }

 @GetMapping("/{id}")
 public SwapOrder get(@RequestHeader(value = DemoUser.HEADER, required = false) String user, @PathVariable UUID id) {
  return repo.find(DemoUser.resolve(user), id).orElseThrow(() -> ApiException.notFound("SWAP_NOT_FOUND", "Swap not found"));
 }
}
