package com.spk.tfholdings

import com.spk.tfholdings.data.Quote

/** Made-up holdings (not anyone's real portfolio) for tests and CI screenshots. */
object Samples {
    const val JSON = """
{"version": 1, "app": "TechnoFunda", "generated_at": "2026-11-03T09:30:00",
 "holdings": [
  {"ticker": "RELIANCE", "name": "Reliance Industries", "bucket": "Core", "broker": "Zerodha", "sector": "Energy",
   "qty": 40, "avg_cost": 1250.0, "invested": 50000.0, "last_price": 1300.0, "prev_close": 1290.0, "tranche": "Tranche 2", "stop": 0},
  {"ticker": "TCS", "name": "Tata Consultancy Services", "bucket": "Core", "broker": "Dhan", "sector": "IT",
   "qty": 20, "avg_cost": 3600.0, "invested": 72000.0, "last_price": 3400.0, "prev_close": 3420.0, "tranche": "Tranche 1 (Pilot)", "stop": 0},
  {"ticker": "BAJAJ-AUTO", "name": "Bajaj Auto", "bucket": "Satellite", "broker": "Fyers", "sector": "Auto",
   "qty": 10, "avg_cost": 8000.0, "invested": 80000.0, "last_price": 8800.0, "prev_close": 8700.0, "tranche": "Tranche 3", "stop": 8000.0},
  {"ticker": "M&M", "name": "Mahindra & Mahindra", "bucket": "Satellite", "broker": "Zerodha", "sector": "Auto",
   "qty": 15, "avg_cost": 2900.0, "invested": 43500.0, "last_price": 3100.0, "prev_close": 3120.0, "tranche": "Tranche 1", "stop": 2650.0},
  {"ticker": "GOLDBEES", "name": "Gold ETF", "bucket": "Gold/MF", "broker": "Dhan", "sector": "",
   "qty": 500, "avg_cost": 70.0, "invested": 35000.0, "last_price": 78.0, "prev_close": 77.5, "tranche": "", "stop": 0},
  {"ticker": "SGBTEST", "name": "Sovereign Gold Bond", "bucket": "Gold/MF", "broker": "Zerodha", "sector": "",
   "qty": 10, "avg_cost": 5000.0, "invested": 50000.0, "last_price": 15000.0, "prev_close": 0, "tranche": "", "stop": 0},
  {"ticker": "120503", "name": "An Index Fund - Direct Growth", "bucket": "Gold/MF", "broker": "Fyers", "sector": "",
   "qty": 1234.567, "avg_cost": 80.0, "invested": 98765.36, "last_price": 92.5, "prev_close": 92.1, "tranche": "", "stop": 0}
 ],
 "cash": [{"broker": "Zerodha", "amount": 250000.0}, {"broker": "Bank", "amount": 150000.0}],
 "other_assets": [
  {"name": "Provident fund", "asset_class": "Fixed Income", "value": 1500000.0, "maturity_date": null, "kind": "PF / EPF"},
  {"name": "Child plan ULIP", "asset_class": "Fixed Income", "value": 600000.0, "maturity_date": "2027-01-08", "kind": "ULIP"}
 ]}
"""

    val quotes = mapOf(
        "RELIANCE" to Quote(1312.4, 1300.0, 0L),
        "TCS" to Quote(3385.0, 3400.0, 0L),
        "BAJAJ-AUTO" to Quote(8890.0, 8800.0, 0L),
        "GOLDBEES" to Quote(78.6, 78.0, 0L),
        "120503" to Quote(92.8, 92.5, 0L),
        "SGBTEST" to Quote(15800.0, 15000.0, 0L),
    )
}
