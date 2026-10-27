package dev.opencrypto.walletservice.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Average-cost positions for profit and loss.
 *
 * Acquiring adds quantity and its USD cost. Disposing removes quantity at the current average
 * cost and books realised P/L = proceeds - cost removed. Swap fees therefore show up as a
 * realised loss on the asset you paid with. Unrealised P/L = market value - remaining cost basis.
 */
@Service
public class PositionService {
 public record Position(String asset, BigDecimal quantity, BigDecimal costBasisUsd, BigDecimal realizedPnlUsd) {
  public BigDecimal averageCostUsd() {
   return quantity.signum() == 0 ? BigDecimal.ZERO : costBasisUsd.divide(quantity, 10, RoundingMode.HALF_UP);
  }
 }

 private final JdbcClient jdbc;

 public PositionService(JdbcClient jdbc) { this.jdbc = jdbc; }

 @Transactional(propagation = Propagation.MANDATORY)
 public void acquire(String userId, String asset, BigDecimal quantity, BigDecimal costUsd) {
  jdbc.sql("""
    INSERT INTO position (user_id, asset, quantity, cost_basis_usd) VALUES (:u, :a, :q, :c)
    ON CONFLICT (user_id, asset) DO UPDATE SET quantity = position.quantity + :q, cost_basis_usd = position.cost_basis_usd + :c, updated_at = now()""")
   .param("u", userId).param("a", asset).param("q", quantity).param("c", costUsd.setScale(10, RoundingMode.HALF_UP)).update();
 }

 @Transactional(propagation = Propagation.MANDATORY)
 public void dispose(String userId, String asset, BigDecimal quantity, BigDecimal proceedsUsd) {
  Position p = lock(userId, asset).orElse(new Position(asset, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
  BigDecimal costRemoved = p.quantity().signum() == 0 ? BigDecimal.ZERO
   : p.costBasisUsd().multiply(quantity.min(p.quantity())).divide(p.quantity(), 10, RoundingMode.HALF_UP);
  BigDecimal realized = proceedsUsd.subtract(costRemoved).setScale(10, RoundingMode.HALF_UP);
  jdbc.sql("""
    INSERT INTO position (user_id, asset, quantity, cost_basis_usd, realized_pnl_usd) VALUES (:u, :a, 0, 0, :r)
    ON CONFLICT (user_id, asset) DO UPDATE SET quantity = GREATEST(position.quantity - :q, 0),
      cost_basis_usd = GREATEST(position.cost_basis_usd - :c, 0), realized_pnl_usd = position.realized_pnl_usd + :r, updated_at = now()""")
   .param("u", userId).param("a", asset).param("q", quantity).param("c", costRemoved).param("r", realized).update();
 }

 @Transactional(propagation = Propagation.MANDATORY)
 public void clear(String userId) {
  jdbc.sql("DELETE FROM position WHERE user_id = :u").param("u", userId).update();
 }

 public Map<String, Position> positions(String userId) {
  Map<String, Position> out = new TreeMap<>();
  jdbc.sql("SELECT asset, quantity, cost_basis_usd, realized_pnl_usd FROM position WHERE user_id = :u").param("u", userId)
   .query(rs -> { out.put(rs.getString(1), new Position(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4))); });
  return out;
 }

 private Optional<Position> lock(String userId, String asset) {
  return jdbc.sql("SELECT asset, quantity, cost_basis_usd, realized_pnl_usd FROM position WHERE user_id = :u AND asset = :a FOR UPDATE")
   .param("u", userId).param("a", asset)
   .query((rs, i) -> new Position(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4))).optional();
 }
}
