package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.R
import com.silentpulse.messenger.feature.stocks.data.StockSymbols
import java.util.Locale

data class StockAsset(val symbol: String, val label: Int, val aliases: List<String>)

object StockAssets {
    val entries = listOf(
        StockAsset("^GSPC", R.string.stock_asset_sp500, listOf("s&p 500", "s&p500", "snp500", "snp 500", "sp500", "s and p 500")),
        StockAsset("^DJI", R.string.stock_asset_dow, listOf("dow", "dow jones")),
        StockAsset("^IXIC", R.string.stock_asset_nasdaq, listOf("nasdaq", "nasdaq composite")),
        StockAsset("^NDX", R.string.stock_asset_nasdaq100, listOf("nasdaq 100", "nasdaq100")),
        StockAsset("^RUT", R.string.stock_asset_russell, listOf("russell 2000", "russell2000")),
        StockAsset("GC=F", R.string.stock_asset_gold, listOf("gold")),
        StockAsset("SI=F", R.string.stock_asset_silver, listOf("silver")),
        StockAsset("PL=F", R.string.stock_asset_platinum, listOf("platinum")),
        StockAsset("HG=F", R.string.stock_asset_copper, listOf("copper")),
        StockAsset("CL=F", R.string.stock_asset_crude, listOf("crude oil", "oil", "wti", "wti crude")),
        StockAsset("BZ=F", R.string.stock_asset_brent, listOf("brent", "brent oil")),
        StockAsset("NG=F", R.string.stock_asset_gas, listOf("natural gas")),
        StockAsset("ZW=F", R.string.stock_asset_wheat, listOf("wheat")),
        StockAsset("ZC=F", R.string.stock_asset_corn, listOf("corn")),
        StockAsset("ZS=F", R.string.stock_asset_soy, listOf("soybeans", "soy")),
        StockAsset("KC=F", R.string.stock_asset_coffee, listOf("coffee")),
        StockAsset("CC=F", R.string.stock_asset_cocoa, listOf("cocoa")),
        StockAsset("SB=F", R.string.stock_asset_sugar, listOf("sugar")),
        StockAsset("CT=F", R.string.stock_asset_cotton, listOf("cotton")),
        StockAsset("BND", R.string.stock_asset_total_bond, listOf("total bond", "total bond market")),
        StockAsset("AGG", R.string.stock_asset_aggregate_bond, listOf("aggregate bonds")),
        StockAsset("TLT", R.string.stock_asset_long_treasury, listOf("long treasury", "long term treasury")),
        StockAsset("IEF", R.string.stock_asset_medium_treasury, listOf("intermediate treasury")),
        StockAsset("SHY", R.string.stock_asset_short_treasury, listOf("short treasury")),
        StockAsset("TIP", R.string.stock_asset_tips, listOf("inflation protected bonds", "tips")),
        StockAsset("LQD", R.string.stock_asset_corporate_bond, listOf("corporate bonds")),
        StockAsset("ZT=F", R.string.stock_asset_two_year, listOf("2 year treasury", "2 year treasury futures")),
        StockAsset("ZF=F", R.string.stock_asset_five_year, listOf("5 year treasury", "5 year treasury futures")),
        StockAsset("ZN=F", R.string.stock_asset_ten_year, listOf("10 year treasury", "10 year treasury futures")),
        StockAsset("ZB=F", R.string.stock_asset_thirty_year, listOf("30 year treasury", "treasury bond futures"))
    )
    private val aliases = entries.flatMap { asset -> asset.aliases.map { it to asset.symbol } }.toMap()

    fun search(query: String, label: (Int) -> String): List<StockAsset> {
        val terms = query.trim().removePrefix("$").lowercase(Locale.ROOT)
            .split(Regex("\\s+")).filter(String::isNotEmpty)
        return entries.filter { asset ->
            val searchable = (listOf(asset.symbol, label(asset.label)) + asset.aliases)
                .joinToString(" ").lowercase(Locale.ROOT)
            terms.all(searchable::contains)
        }
    }

    fun resolve(raw: String): String? {
        val text = raw.trim()
        if (text.startsWith("$")) return StockSymbols.normalize(text.drop(1))
        return aliases[text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")] ?: StockSymbols.normalize(text)
    }

    fun editorSymbol(symbol: String): String = if (resolve(symbol) == symbol) symbol else "$$symbol"
}
