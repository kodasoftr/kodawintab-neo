package com.example.driver

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

private val Context.tabletConfigStore by preferencesDataStore(name = "koda_tablet_configs")

/**
 * Lazy-loading repository for OTD-inspired JSON tablet profiles + persistent DataStore configs.
 * Profiles are indexed by VID/PID and cached on first lookup to keep startup < 1s.
 */
class TabletProfileRepository(private val context: Context) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val profileAdapter = moshi.adapter(TabletProfileJson::class.java)
    private val cachedProfiles = ConcurrentHashMap<String, TabletProfileJson>()
    @Volatile private var loadedAll = false

    @Synchronized
    fun getAllProfiles(): List<TabletProfileJson> {
        if (!loadedAll) {
            val assetFiles = context.assets.list("tablets").orEmpty()
            for (fileName in assetFiles) {
                if (!fileName.endsWith(".json")) continue
                runCatching {
                    context.assets.open("tablets/$fileName").bufferedReader().use { reader ->
                        val profile = profileAdapter.fromJson(reader.readText())
                        if (profile != null) {
                            cachedProfiles[profile.id] = profile
                        }
                    }
                }
            }
            loadedAll = true
        }
        return cachedProfiles.values.sortedWith(
            compareBy<TabletProfileJson> { if (it.id == "ugee_s640") 0 else 1 }
                .thenBy { if (it.isPenDisplay) 1 else 0 }
                .thenBy { it.name }
        )
    }

    fun getPenDisplayProfiles(): List<TabletProfileJson> =
        getAllProfiles().filter { it.isPenDisplay }

    fun findByVidPid(vendorId: Int, productId: Int, productName: String = ""): TabletProfileJson? {
        val all = getAllProfiles()
        val lower = productName.lowercase()
        val looksLikePenDisplay = PEN_DISPLAY_NAME_HINTS.any { lower.contains(it) }
        if (looksLikePenDisplay) {
            val displayExact = all.firstOrNull { it.isPenDisplay && it.vendorId == vendorId && productId in it.productIds }
            if (displayExact != null) return displayExact
            val displayByBrand = all.firstOrNull { it.isPenDisplay && it.vendorId == vendorId }
            if (displayByBrand != null) return displayByBrand
        }
        val exact = all.firstOrNull { it.vendorId == vendorId && productId in it.productIds }
        if (exact != null) return exact
        return all.firstOrNull { !it.isPenDisplay && it.vendorId == vendorId }
            ?: all.firstOrNull { it.vendorId == vendorId }
    }

    companion object {
        val PEN_DISPLAY_NAME_HINTS = listOf(
            "pen display", "artist", "kamvas", "cintiq", "wacom one", "ue12", "u1200", "innovator", "kanvas", "screen tablet"
        )
    }

    fun getProfileById(id: String): TabletProfileJson {
        return getAllProfiles().firstOrNull { it.id == id }
            ?: TabletProfileJson(
                id = "generic_hid",
                name = "Mesa HID Genérica",
                brand = "Genérico",
                vendorId = 0,
                productIds = emptyList(),
                widthMm = 160f,
                heightMm = 100f,
                maxX = 32767,
                maxY = 32767,
                maxPressure = 8191,
                lpi = 5080,
                pollingRateHz = 200,
                interfaceNumber = 0,
                endpointAddress = 129,
                parserType = ParserType.GENERIC_HID.name
            )
    }

    fun createParserFor(
        profile: TabletProfileJson,
        rawDescriptors: ByteArray? = null
    ): HidReportParser {
        val decoded = HidDescriptorDecoder.decode(rawDescriptors)
        val useDecodedBounds = rawDescriptors != null &&
            (decoded.hasDigitizerUsagePage || profile.id == "generic_hid")
        val effMaxX = if (useDecodedBounds && decoded.logicalMaxX > profile.maxX) {
            decoded.logicalMaxX
        } else {
            profile.maxX.coerceAtLeast(1024)
        }
        val effMaxY = if (useDecodedBounds && decoded.logicalMaxY > profile.maxY) {
            decoded.logicalMaxY
        } else {
            profile.maxY.coerceAtLeast(1024)
        }
        val effMaxP = if (useDecodedBounds && decoded.logicalMaxPressure > profile.maxPressure) {
            decoded.logicalMaxPressure
        } else {
            profile.maxPressure.coerceAtLeast(1024)
        }

        val layout = decoded.copy(
            logicalMaxX = effMaxX,
            logicalMaxY = effMaxY,
            logicalMaxPressure = effMaxP
        )
        val generic = GenericHidDigitizerParser(layout)
        val ugee = UgeeS640Parser(effMaxX, effMaxY, effMaxP)
        val huion = HuionParser(effMaxX, effMaxY, effMaxP, bit5IsEraser = true, brand = TabletBrand.HUION)
        val ucLogic = HuionParser(effMaxX, effMaxY, effMaxP, bit5IsEraser = false, brand = TabletBrand.UCLOGIC)
        val veikk = VeikkParser(effMaxX, effMaxY, effMaxP)
        val wacom = WacomParser(effMaxX, effMaxY, effMaxP)

        return when (runCatching { ParserType.valueOf(profile.parserType) }.getOrNull()) {
            ParserType.UGEE_S640 -> ResilientTabletParser(ugee, listOf(huion, veikk, wacom, generic))
            ParserType.WACOM -> ResilientTabletParser(wacom, listOf(ugee, huion, veikk, generic))
            ParserType.HUION -> ResilientTabletParser(huion, listOf(ucLogic, ugee, veikk, wacom, generic))
            ParserType.UCLOGIC -> ResilientTabletParser(ucLogic, listOf(huion, ugee, veikk, wacom, generic))
            ParserType.VEIKK -> ResilientTabletParser(veikk, listOf(ugee, ucLogic, huion, wacom, generic))
            ParserType.XP_PEN -> ResilientTabletParser(
                XpPenParser(effMaxX, effMaxY, effMaxP),
                listOf(ucLogic, huion, veikk, wacom, generic)
            )
            else -> ResilientTabletParser(generic, listOf(ugee, ucLogic, huion, veikk, wacom))
        }
    }

    suspend fun saveConfig(config: TabletConfig) {
        val prefix = config.slotId.name
        context.tabletConfigStore.edit { prefs ->
            prefs[stringPreferencesKey("${prefix}_profileId")] = config.profileId
            prefs[booleanPreferencesKey("${prefix}_autoRot")] = config.autoRotation
            prefs[booleanPreferencesKey("${prefix}_rotAxes")] = config.rotateTabletAxesWithScreen
            prefs[intPreferencesKey("${prefix}_manualRot")] = config.manualRotationDeg
            prefs[booleanPreferencesKey("${prefix}_invX")] = config.invertX
            prefs[booleanPreferencesKey("${prefix}_invY")] = config.invertY
            prefs[floatPreferencesKey("${prefix}_actL")] = config.activeArea.leftNorm
            prefs[floatPreferencesKey("${prefix}_actT")] = config.activeArea.topNorm
            prefs[floatPreferencesKey("${prefix}_actW")] = config.activeArea.widthNorm
            prefs[floatPreferencesKey("${prefix}_actH")] = config.activeArea.heightNorm
            prefs[floatPreferencesKey("${prefix}_scrL")] = config.screenArea.leftNorm
            prefs[floatPreferencesKey("${prefix}_scrT")] = config.screenArea.topNorm
            prefs[floatPreferencesKey("${prefix}_scrW")] = config.screenArea.widthNorm
            prefs[floatPreferencesKey("${prefix}_scrH")] = config.screenArea.heightNorm
            prefs[booleanPreferencesKey("${prefix}_lockRatio")] = config.lockAspectRatio
            prefs[stringPreferencesKey("${prefix}_dpiMode")] = config.dpiMode.name
            prefs[intPreferencesKey("${prefix}_dpiX")] = config.manualDpiX
            prefs[intPreferencesKey("${prefix}_dpiY")] = config.manualDpiY
            prefs[floatPreferencesKey("${prefix}_minP")] = config.minPressureThreshold
            prefs[floatPreferencesKey("${prefix}_maxP")] = config.maxPressureCap
            prefs[booleanPreferencesKey("${prefix}_fJitter")] = config.filters.antiJitterEnabled
            prefs[floatPreferencesKey("${prefix}_fJitterR")] = config.filters.antiJitterRadius
            prefs[booleanPreferencesKey("${prefix}_fSmooth")] = config.filters.smoothingEnabled
            prefs[floatPreferencesKey("${prefix}_fSmoothA")] = config.filters.smoothingAlpha
            prefs[booleanPreferencesKey("${prefix}_fOneEuro")] = config.filters.oneEuroEnabled
            prefs[floatPreferencesKey("${prefix}_fOneEuroCut")] = config.filters.oneEuroMinCutoff
            prefs[floatPreferencesKey("${prefix}_fOneEuroBeta")] = config.filters.oneEuroBeta
            prefs[booleanPreferencesKey("${prefix}_fTilt")] = config.filters.tiltCompensationEnabled
            prefs[floatPreferencesKey("${prefix}_fTiltF")] = config.filters.tiltFactor
            prefs[booleanPreferencesKey("${prefix}_fHyst")] = config.filters.hysteresisEnabled
            prefs[floatPreferencesKey("${prefix}_fHystT")] = config.filters.hysteresisThreshold
            prefs[booleanPreferencesKey("${prefix}_fPred")] = config.filters.predictionEnabled
            prefs[floatPreferencesKey("${prefix}_fPredMs")] = config.filters.predictionLeadMs
            prefs[stringPreferencesKey("${prefix}_outMode")] = config.outputMode.name
            prefs[floatPreferencesKey("${prefix}_relSens")] = config.relativeSensitivity
            prefs[intPreferencesKey("${prefix}_dispId")] = config.targetDisplayId
            val pkg = config.targetAppPackage
            if (pkg != null) {
                prefs[stringPreferencesKey("${prefix}_appPkg")] = pkg
            } else {
                prefs.remove(stringPreferencesKey("${prefix}_appPkg"))
            }
            prefs[stringPreferencesKey("${prefix}_curve")] = config.pressureCurve
                .joinToString(";") { "${it.inputNorm},${it.outputNorm}" }
            prefs[stringPreferencesKey("${prefix}_bindTip")] = config.bindings.tipAction
            prefs[stringPreferencesKey("${prefix}_bindBtn1")] = config.bindings.button1Action
            prefs[stringPreferencesKey("${prefix}_bindBtn2")] = config.bindings.button2Action
            prefs[stringPreferencesKey("${prefix}_bindExp")] = config.bindings.expressKeyActions.entries
                .sortedBy { it.key }
                .joinToString("||") { "${it.key}=${it.value.replace("||", " ")}" }
        }
    }

    suspend fun loadConfig(slotId: TabletSlotId): TabletConfig {
        val prefix = slotId.name
        val prefs = context.tabletConfigStore.data.first()
        val defaultCfg = TabletConfig(slotId = slotId)
        val curveStr = prefs[stringPreferencesKey("${prefix}_curve")]
        val parsedCurve = curveStr?.split(";")?.mapNotNull { pair ->
            val parts = pair.split(",")
            if (parts.size == 2) {
                val x = parts[0].toFloatOrNull()
                val y = parts[1].toFloatOrNull()
                if (x != null && y != null) PressureControlPoint(x, y) else null
            } else null
        }?.takeIf { it.size >= 2 } ?: defaultCfg.pressureCurve

        val expRaw = prefs[stringPreferencesKey("${prefix}_bindExp")]
        val parsedExp = if (!expRaw.isNullOrBlank()) {
            expRaw.split("||").mapNotNull { item ->
                val eq = item.indexOf('=')
                if (eq > 0) {
                    val idx = item.substring(0, eq).toIntOrNull()
                    val act = item.substring(eq + 1)
                    if (idx != null) idx to act else null
                } else null
            }.toMap().takeIf { it.isNotEmpty() } ?: defaultCfg.bindings.expressKeyActions
        } else {
            defaultCfg.bindings.expressKeyActions
        }

        return TabletConfig(
            slotId = slotId,
            profileId = prefs[stringPreferencesKey("${prefix}_profileId")] ?: defaultCfg.profileId,
            autoRotation = prefs[booleanPreferencesKey("${prefix}_autoRot")] ?: defaultCfg.autoRotation,
            rotateTabletAxesWithScreen = prefs[booleanPreferencesKey("${prefix}_rotAxes")] ?: defaultCfg.rotateTabletAxesWithScreen,
            manualRotationDeg = prefs[intPreferencesKey("${prefix}_manualRot")] ?: defaultCfg.manualRotationDeg,
            invertX = prefs[booleanPreferencesKey("${prefix}_invX")] ?: defaultCfg.invertX,
            invertY = prefs[booleanPreferencesKey("${prefix}_invY")] ?: defaultCfg.invertY,
            activeArea = ActiveAreaRect(
                leftNorm = prefs[floatPreferencesKey("${prefix}_actL")] ?: 0f,
                topNorm = prefs[floatPreferencesKey("${prefix}_actT")] ?: 0f,
                widthNorm = prefs[floatPreferencesKey("${prefix}_actW")] ?: 1f,
                heightNorm = prefs[floatPreferencesKey("${prefix}_actH")] ?: 1f
            ),
            screenArea = ActiveAreaRect(
                leftNorm = prefs[floatPreferencesKey("${prefix}_scrL")] ?: 0f,
                topNorm = prefs[floatPreferencesKey("${prefix}_scrT")] ?: 0f,
                widthNorm = prefs[floatPreferencesKey("${prefix}_scrW")] ?: 1f,
                heightNorm = prefs[floatPreferencesKey("${prefix}_scrH")] ?: 1f
            ),
            lockAspectRatio = prefs[booleanPreferencesKey("${prefix}_lockRatio")] ?: true,
            dpiMode = runCatching {
                DpiNormalizationMode.valueOf(prefs[stringPreferencesKey("${prefix}_dpiMode")] ?: DpiNormalizationMode.AUTO.name)
            }.getOrDefault(DpiNormalizationMode.AUTO),
            manualDpiX = (prefs[intPreferencesKey("${prefix}_dpiX")] ?: 420).coerceIn(72, 1200),
            manualDpiY = (prefs[intPreferencesKey("${prefix}_dpiY")] ?: 420).coerceIn(72, 1200),
            minPressureThreshold = prefs[floatPreferencesKey("${prefix}_minP")] ?: 0.01f,
            maxPressureCap = prefs[floatPreferencesKey("${prefix}_maxP")] ?: 1f,
            pressureCurve = parsedCurve,
            filters = FilterSettings(
                antiJitterEnabled = prefs[booleanPreferencesKey("${prefix}_fJitter")] ?: false,
                antiJitterRadius = prefs[floatPreferencesKey("${prefix}_fJitterR")] ?: 2.5f,
                smoothingEnabled = prefs[booleanPreferencesKey("${prefix}_fSmooth")] ?: false,
                smoothingAlpha = prefs[floatPreferencesKey("${prefix}_fSmoothA")] ?: 0.35f,
                oneEuroEnabled = prefs[booleanPreferencesKey("${prefix}_fOneEuro")] ?: false,
                oneEuroMinCutoff = prefs[floatPreferencesKey("${prefix}_fOneEuroCut")] ?: 1.6f,
                oneEuroBeta = prefs[floatPreferencesKey("${prefix}_fOneEuroBeta")] ?: 0.08f,
                tiltCompensationEnabled = prefs[booleanPreferencesKey("${prefix}_fTilt")] ?: false,
                tiltFactor = prefs[floatPreferencesKey("${prefix}_fTiltF")] ?: 0.25f,
                hysteresisEnabled = prefs[booleanPreferencesKey("${prefix}_fHyst")] ?: false,
                hysteresisThreshold = prefs[floatPreferencesKey("${prefix}_fHystT")] ?: 4.0f,
                predictionEnabled = prefs[booleanPreferencesKey("${prefix}_fPred")] ?: false,
                predictionLeadMs = prefs[floatPreferencesKey("${prefix}_fPredMs")] ?: 3.0f
            ),
            outputMode = runCatching {
                OutputMode.valueOf(prefs[stringPreferencesKey("${prefix}_outMode")] ?: OutputMode.ABSOLUTE.name)
            }.getOrDefault(OutputMode.ABSOLUTE),
            relativeSensitivity = prefs[floatPreferencesKey("${prefix}_relSens")] ?: 1.4f,
            targetDisplayId = prefs[intPreferencesKey("${prefix}_dispId")] ?: 0,
            targetAppPackage = prefs[stringPreferencesKey("${prefix}_appPkg")],
            bindings = PenBindings(
                tipAction = prefs[stringPreferencesKey("${prefix}_bindTip")] ?: defaultCfg.bindings.tipAction,
                button1Action = prefs[stringPreferencesKey("${prefix}_bindBtn1")] ?: defaultCfg.bindings.button1Action,
                button2Action = prefs[stringPreferencesKey("${prefix}_bindBtn2")] ?: defaultCfg.bindings.button2Action,
                expressKeyActions = parsedExp
            )
        )
    }
}
