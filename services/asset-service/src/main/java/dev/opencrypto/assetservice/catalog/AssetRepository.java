package dev.opencrypto.assetservice.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import dev.opencrypto.platform.web.ApiException;

/**
 * SQL access to the catalogue. Sorting and filtering are built from a whitelist, never by
 * concatenating user input, so the {@code sort} parameter cannot inject SQL.
 */
@Repository
public class AssetRepository {
 /** API sort key -> SQL expression. */
 static final Map<String, String> SORTS = Map.of(
  "marketCap", "market_cap_usd", "price", "price_usd", "change24h", "change_24h_pct",
  "volume", "volume_24h_usd", "name", "name", "symbol", "symbol", "rank", "market_cap_rank");

 public record PageQuery(int page, int size, String sortKey, boolean ascending, String search, String kind) {}

 private static final String COLUMNS = """
  symbol, coingecko_id, name, kind, decimals, image_url, description, networks::text AS networks,
  sim_initial_price, sim_annual_volatility, sim_annual_drift, price_usd, market_cap_usd, volume_24h_usd,
  change_24h_pct, market_cap_rank, price_source, price_updated_at""";

 private final JdbcClient jdbc;

 public AssetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

 public List<CatalogAsset> findAllActive() {
  return jdbc.sql("SELECT " + COLUMNS + " FROM asset WHERE active ORDER BY symbol").query(AssetRepository::map).list();
 }

 public Optional<CatalogAsset> find(String symbol) {
  return jdbc.sql("SELECT " + COLUMNS + " FROM asset WHERE active AND symbol = :s").param("s", symbol.toUpperCase(Locale.ROOT))
   .query(AssetRepository::map).optional();
 }

 public CatalogAsset require(String symbol) {
  return find(symbol).orElseThrow(() -> ApiException.notFound("UNKNOWN_ASSET", "Unknown or unsupported asset: " + symbol));
 }

 public List<CatalogAsset> page(PageQuery q) {
  String sortColumn = SORTS.get(q.sortKey());
  if (sortColumn == null) throw ApiException.badRequest("INVALID_SORT", "sort must be one of " + new TreeSet<>(SORTS.keySet()));
  String order = sortColumn + (q.ascending() ? " ASC NULLS LAST" : " DESC NULLS LAST") + ", symbol ASC";
  return jdbc.sql("SELECT " + COLUMNS + " FROM asset WHERE " + where() + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
   .params(filterParams(q)).param("limit", q.size()).param("offset", (long) q.page() * q.size())
   .query(AssetRepository::map).list();
 }

 public long count(PageQuery q) {
  return jdbc.sql("SELECT count(*) FROM asset WHERE " + where()).params(filterParams(q)).query(Long.class).single();
 }

 private static String where() {
  return "active AND (CAST(:search AS text) IS NULL OR symbol ILIKE :pattern OR name ILIKE :pattern) AND (CAST(:kind AS text) IS NULL OR kind = :kind)";
 }

 private static Map<String, Object> filterParams(PageQuery q) {
  Map<String, Object> p = new HashMap<>();
  String search = q.search() == null || q.search().isBlank() ? null : q.search().trim();
  p.put("search", search);
  p.put("pattern", search == null ? "" : "%" + search.replace("%", "\\%").replace("_", "\\_") + "%");
  p.put("kind", q.kind() == null || q.kind().isBlank() ? null : q.kind().toUpperCase(Locale.ROOT));
  return p;
 }

 static CatalogAsset map(ResultSet rs, int row) throws SQLException {
  Timestamp updated = rs.getTimestamp("price_updated_at");
  Integer rank = rs.getObject("market_cap_rank", Integer.class);
  return new CatalogAsset(rs.getString("symbol"), rs.getString("coingecko_id"), rs.getString("name"), rs.getString("kind"),
   rs.getInt("decimals"), rs.getString("image_url"), rs.getString("description"), rs.getString("networks"),
   rs.getBigDecimal("sim_initial_price"), rs.getBigDecimal("sim_annual_volatility"), rs.getBigDecimal("sim_annual_drift"),
   rs.getBigDecimal("price_usd"), rs.getBigDecimal("market_cap_usd"), rs.getBigDecimal("volume_24h_usd"),
   rs.getBigDecimal("change_24h_pct"), rank,
   rs.getString("price_source"), updated == null ? null : updated.toInstant());
 }
}
