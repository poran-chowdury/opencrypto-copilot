package dev.opencrypto.walletservice.ledger;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import dev.opencrypto.platform.web.ApiException;

public class InsufficientFundsException extends ApiException {
 public InsufficientFundsException(String asset, BigDecimal available, BigDecimal required) {
  super(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS",
   "Insufficient %s balance: available %s, required %s".formatted(asset, available.stripTrailingZeros().toPlainString(), required.stripTrailingZeros().toPlainString()));
 }
}
