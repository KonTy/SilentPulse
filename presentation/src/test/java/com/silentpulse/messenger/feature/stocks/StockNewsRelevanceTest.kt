package com.silentpulse.messenger.feature.stocks

import org.junit.Assert.*
import org.junit.Test

class StockNewsRelevanceTest {
    @Test
    fun `Apple-tagged T-Mobile and Nvidia headlines are marked as possible indirect mentions`() {
        assertFalse(StockNewsRelevance.headlineNamesInstrument(
            "T-Mobile raises another fee customers pay each month", "AAPL", "Apple Inc.", "EQUITY"
        ))
        assertFalse(StockNewsRelevance.headlineNamesInstrument(
            "Nvidia announces a new buyback", "AAPL", "Apple Inc.", "EQUITY"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "Apple announces new products", "AAPL", "Apple Inc.", "EQUITY"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "AAPL earnings preview", "AAPL", null, null
        ))
    }

    @Test
    fun `name matching respects word boundaries and corporate suffixes`() {
        assertFalse(StockNewsRelevance.headlineNamesInstrument(
            "Pineapple growers report a record crop", "AAPL", "Apple Inc.", "EQUITY"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "Microsoft raises guidance", "MSFT", "Microsoft Corporation", "EQUITY"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "Google announces a new service", "GOOGL", "Alphabet Inc.", "EQUITY"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "T-Mobile raises fees", "TMUS", "T-Mobile US, Inc.", "EQUITY"
        ))
    }

    @Test
    fun `ordinary words and single-letter tickers are not treated as stock mentions`() {
        assertFalse(StockNewsRelevance.headlineNamesInstrument(
            "A strong year for investors", "A", null, null
        ))
        assertFalse(StockNewsRelevance.headlineNamesInstrument(
            "ALL investors should watch this", "ALL", "Allstate Corporation", "EQUITY"
        ))
        assertFalse(StockNewsRelevance.headlineNamesInstrument(
            "New AI software arrives", "AI", "C3.ai, Inc.", "EQUITY"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "NYSE: F earnings update", "F", null, null
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "An update on \$AI", "AI", null, null
        ))
    }

    @Test
    fun `indices commodities crypto and funds use instrument-specific names`() {
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "S&P 500 climbs", "^GSPC", "S&P 500", "INDEX"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "Gold futures rise", "GC=F", "Gold December", "FUTURE"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "Bitcoin reaches a new high", "BTC-USD", "Bitcoin USD", "CRYPTOCURRENCY"
        ))
        assertFalse(StockNewsRelevance.headlineNamesInstrument(
            "Fidelity launches a bond fund", "FXAIX", "Fidelity 500 Index Fund", "MUTUALFUND"
        ))
        assertTrue(StockNewsRelevance.headlineNamesInstrument(
            "FXAIX fee comparison", "FXAIX", "Fidelity 500 Index Fund", "MUTUALFUND"
        ))
    }
}
