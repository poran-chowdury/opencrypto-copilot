-- Supported assets. Network fees/confirmations are illustrative values used by the simulated
-- wallet, not live chain data. sim_* columns drive the simulated provider only.
INSERT INTO asset (symbol, coingecko_id, name, kind, decimals, description, networks, sim_initial_price, sim_annual_volatility, sim_annual_drift) VALUES
('USD',  NULL,          'US Dollar',  'FIAT',       2, 'Simulated cash used to price and buy other assets.', '[]', 1, 0, 0),
('BTC',  'bitcoin',     'Bitcoin',    'CRYPTO',     8, 'The first decentralised cryptocurrency.',
  '[{"id":"bitcoin","name":"Bitcoin","nativeChain":true,"withdrawalFee":"0.00012","confirmations":3,"typicalMinutes":30}]', 80000, 0.60, 0.10),
('ETH',  'ethereum',    'Ethereum',   'CRYPTO',     8, 'Smart-contract platform; native asset of Ethereum.',
  '[{"id":"ethereum","name":"Ethereum","nativeChain":true,"withdrawalFee":"0.0015","confirmations":12,"typicalMinutes":3},{"id":"arbitrum","name":"Arbitrum One","nativeChain":false,"withdrawalFee":"0.0002","confirmations":1,"typicalMinutes":1}]', 2300, 0.75, 0.10),
('USDT', 'tether',      'Tether',     'STABLECOIN', 6, 'USD-pegged stablecoin issued on several chains.',
  '[{"id":"ethereum","name":"Ethereum (ERC-20)","nativeChain":false,"withdrawalFee":"4","confirmations":12,"typicalMinutes":3},{"id":"tron","name":"Tron (TRC-20)","nativeChain":false,"withdrawalFee":"1","confirmations":20,"typicalMinutes":2}]', 1, 0, 0),
('BNB',  'binancecoin', 'BNB',        'CRYPTO',     8, 'Native asset of BNB Smart Chain.',
  '[{"id":"bsc","name":"BNB Smart Chain","nativeChain":true,"withdrawalFee":"0.0005","confirmations":15,"typicalMinutes":1}]', 600, 0.70, 0.08),
('SOL',  'solana',      'Solana',     'CRYPTO',     8, 'High-throughput smart-contract chain.',
  '[{"id":"solana","name":"Solana","nativeChain":true,"withdrawalFee":"0.01","confirmations":1,"typicalMinutes":1}]', 150, 1.00, 0.10),
('USDC', 'usd-coin',    'USD Coin',   'STABLECOIN', 6, 'USD-pegged stablecoin.',
  '[{"id":"ethereum","name":"Ethereum (ERC-20)","nativeChain":false,"withdrawalFee":"4","confirmations":12,"typicalMinutes":3},{"id":"solana","name":"Solana (SPL)","nativeChain":false,"withdrawalFee":"1","confirmations":1,"typicalMinutes":1}]', 1, 0, 0),
('XRP',  'ripple',      'XRP',        'CRYPTO',     6, 'Native asset of the XRP Ledger.',
  '[{"id":"xrpl","name":"XRP Ledger","nativeChain":true,"withdrawalFee":"0.25","confirmations":1,"typicalMinutes":1}]', 2, 0.90, 0.05),
('DOGE', 'dogecoin',    'Dogecoin',   'CRYPTO',     8, 'Meme-born proof-of-work currency.',
  '[{"id":"dogecoin","name":"Dogecoin","nativeChain":true,"withdrawalFee":"5","confirmations":6,"typicalMinutes":6}]', 0.15, 1.20, 0),
('ADA',  'cardano',     'Cardano',    'CRYPTO',     6, 'Proof-of-stake smart-contract platform.',
  '[{"id":"cardano","name":"Cardano","nativeChain":true,"withdrawalFee":"1","confirmations":15,"typicalMinutes":5}]', 0.6, 0.95, 0.05),
('TRX',  'tron',        'TRON',       'CRYPTO',     6, 'Native asset of the TRON network.',
  '[{"id":"tron","name":"Tron","nativeChain":true,"withdrawalFee":"1","confirmations":20,"typicalMinutes":2}]', 0.25, 0.70, 0.05),
('AVAX', 'avalanche-2', 'Avalanche',  'CRYPTO',     8, 'Native asset of the Avalanche C-Chain.',
  '[{"id":"avalanche-c","name":"Avalanche C-Chain","nativeChain":true,"withdrawalFee":"0.01","confirmations":1,"typicalMinutes":1}]', 20, 1.10, 0.05),
('LINK', 'chainlink',   'Chainlink',  'CRYPTO',     8, 'Oracle network token (ERC-20).',
  '[{"id":"ethereum","name":"Ethereum (ERC-20)","nativeChain":false,"withdrawalFee":"0.5","confirmations":12,"typicalMinutes":3}]', 14, 1.00, 0.05);

-- Cash and stablecoins start at their peg so balances are valued before the first sync.
UPDATE asset SET price_usd = 1, price_source = 'PEG', price_updated_at = now() WHERE kind IN ('FIAT', 'STABLECOIN');
