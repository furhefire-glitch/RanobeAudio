package com.ranobe.audioreader.domain.audio

import android.content.Context
import android.util.Log
import com.ranobe.audioreader.tts.SileroData
import com.ranobe.audioreader.tts.Symbols
import com.ranobe.audioreader.tts.bin.StressBin
import com.ranobe.audioreader.tts.text.AssetTable
import com.ranobe.audioreader.tts.text.Emoji
import com.ranobe.audioreader.tts.text.HardE
import com.ranobe.audioreader.tts.text.Layers
import com.ranobe.audioreader.tts.text.Marks
import com.ranobe.audioreader.tts.text.Morph
import com.ranobe.audioreader.tts.text.Normalizer
import com.ranobe.audioreader.tts.text.Rules
import com.ranobe.audioreader.tts.text.SentenceType
import com.ranobe.audioreader.tts.text.Stress
import com.ranobe.audioreader.tts.text.StressModels
import com.ranobe.audioreader.tts.text.SymbolNames
import com.ranobe.audioreader.tts.text.TextLayer
import com.ranobe.audioreader.tts.text.YoDict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.LitePyTorchAndroid
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module as EtModule
import org.pytorch.executorch.Tensor as EtTensor
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.exp

class SileroTtsEngine(private val context: Context) : StressModels {
    private lateinit var data: SileroData
    private lateinit var sym: Symbols
    private lateinit var layer: TextLayer
    private var speakers: Map<String, Int> = emptyMap()
    private var bases: Map<String, String> = emptyMap()
    private var types: Boolean = false
    private var lang: String = "ru"

    private var mel: Module? = null
    private var head: Module? = null
    private var backbone: EtModule? = null
    private var accentor: Module? = null
    private var homo: Module? = null

    private val rules = Rules()
    var sampleRate: Int = 48000
        private set
    val isLoaded: Boolean get() = mel != null

    fun speakerNames(): Set<String> = speakers.keys

    suspend fun load(modelDir: File, sampleRate: Int = 48000) = withContext(Dispatchers.IO) {
        require(sampleRate == 24000 || sampleRate == 48000) { "Поддерживаются частоты 24000 и 48000 Гц" }
        release()
        this@SileroTtsEngine.sampleRate = sampleRate
        initData()
        val packJson = JSONObject(File(modelDir, "pack.json").readText())
        sym = Symbols.fromJson(packJson)
        val speakersJson = packJson.getJSONObject("speakers")
        speakers = speakersJson.keys().asSequence().associateWith { speakersJson.getInt(it) }
        bases = packJson.optJSONObject("bases")?.let { json ->
            json.keys().asSequence().associateWith { json.getString(it) }
        }.orEmpty()
        types = packJson.optBoolean("types", false)
        lang = packJson.optString("lang", "ru")
        require(lang == "ru") { "Поддерживаются только русскоязычные паки" }
        layer = Layers.of(lang)

        val threads = fastCores
        LitePyTorchAndroid.setNumThreads(threads)
        mel = LiteModuleLoader.load(File(modelDir, "tts_mel.ptl").absolutePath)
        head = LiteModuleLoader.load(File(modelDir, "head.ptl").absolutePath)
        backbone = EtModule.load(File(modelDir, "backbone.pte").absolutePath, EtModule.LOAD_MODE_FILE, threads)
        accentor = File(modelDir, "accentor.ptl").takeIf { it.isFile }
            ?.let { LiteModuleLoader.load(it.absolutePath) }
            ?: LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/accentor.ptl")
        homo = File(modelDir, "homo.ptl").takeIf { it.isFile }
            ?.let { LiteModuleLoader.load(it.absolutePath) }
            ?: LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/homo.ptl")
        Log.i(TAG, "модель загружена: ${packJson.getString("id")}, голосов ${speakers.size}")
    }

    private fun initData() {
        if (::data.isInitialized) return
        val json = context.assets.open("silero/silero_ru.json").bufferedReader().use { it.readText() }
        val eyo = context.assets.open("eyo_safe.txt").bufferedReader().use { it.readLines().asSequence() }
        data = SileroData(StressBin.File(ByteBuffer.wrap(StressBin.write(json, eyo))))
        Normalizer.morph = runCatching { Morph.open(context) }.getOrNull()
        YoDict.shared = YoDict(data.yo)
        HardE.shared = runCatching { HardE.open(context) }.getOrNull()
        Emoji.shared = runCatching { Emoji.open(context) }.getOrNull()
        SymbolNames.cldr = runCatching { SymbolNames.open(context) }.getOrNull()
        AssetTable.context = context
    }

    suspend fun synthesizeChunk(
        text: String,
        speaker: String,
        userDict: Map<String, String> = emptyMap(),
    ): FloatArray = withContext(Dispatchers.Default) {
        check(isLoaded) { "Модель не загружена" }
        val speakerId = speakers[speaker] ?: throw IllegalArgumentException("Нет голоса: $speaker")
        val questions = layer.questions(data)
        var marked = if (rules.on("exclaim")) Marks.exclaim(text) else text
        if (rules.on("question") && SentenceType.classify(marked, questions, rules) == "general_q") {
            marked = Marks.question(marked, verb = questions::verb)
        }
        val marks = Marks.parse(marked, rules.focusLevel)
        val prepared = layer.prepare(marks.text, sym.allowed, rules)
        if (prepared.none { it != '+' && it in sym.alphabet }) return@withContext FloatArray(0)

        val stress = Stress(data, this@SileroTtsEngine, userDict, rules, layer = layer)
        val (accented, endDot) = stress.modelEnd(stress.apply(prepared, marks.text))
        val stretched = stress.stretchShort(accented)
        val spoken = when (bases[speaker] ?: speaker) {
            "aidar" -> stress.yaOnset(stretched)
            "kseniya", "eugene" -> stress.clusterOnset(stretched)
            else -> stretched
        }
        val seq = sym.sequence(stress.forModel(spoken))
        val sentenceType = SentenceType.classify(marks.text, questions, rules)
        val typeIds = if (types) SentenceType.typeIds(prepared, sentenceType, seq.size, data) else LongArray(seq.size)
        val al = Marks.align(marks.words, spoken, seq.size, sym)

        val pauseFrames = HashMap<Int, Long>()
        fun pause(ms: Int, vararg chars: Char) {
            for (c in chars) sym.symbolToId[c]?.let { pauseFrames[it] = Marks.frames(ms) }
        }
        pause(150, ',', ';', ':')
        pause(250, '–', '—')
        val symbDurs = (2 until seq.size).mapNotNull { i ->
            val fr = pauseFrames[seq[i].toInt()] ?: return@mapNotNull null
            val afterPunctDash = spoken[i - 1] in "–—" &&
                spoken.substring(0, i - 1).trimEnd().lastOrNull()?.let { it in Marks.PUNCT } == true
            if (afterPunctDash) null else i.toLong() to fr
        }.toMap() + (if (endDot) mapOf((seq.size - 2).toLong() to 1L) else emptyMap()) + al.symbDurs

        synthesize(seq, speakerId, sampleRate, al.rates, al.pitches, typeIds, al.focus, symbDurs, types)
    }

    private fun synthesize(
        seq: LongArray, speakerId: Int, sampleRate: Int, rates: FloatArray, pitches: FloatArray,
        typeIds: LongArray, focus: LongArray, symbDurs: Map<Long, Long>, types: Boolean,
    ): FloatArray {
        val n = seq.size.toLong()
        val args = arrayListOf(
            IValue.from(Tensor.fromBlob(seq, longArrayOf(1, n))),
            IValue.from(Tensor.fromBlob(longArrayOf(speakerId.toLong()), longArrayOf(1))),
            IValue.from(sampleRate.toLong()),
            if (symbDurs.isEmpty()) IValue.optionalNull() else IValue.dictLongKeyFrom(symbDurs.mapValues { IValue.from(it.value) }),
            IValue.from(Tensor.fromBlob(rates, longArrayOf(1, n))),
            IValue.from(Tensor.fromBlob(pitches, longArrayOf(1, n))),
            IValue.optionalNull(),
            IValue.optionalNull(),
            IValue.from("cpu"),
            IValue.from(-1L),
            IValue.from(false),
        )
        if (types) {
            args += IValue.from(Tensor.fromBlob(typeIds, longArrayOf(1, n)))
            args += if (focus.all { it == 0L }) IValue.optionalNull() else IValue.from(Tensor.fromBlob(focus, longArrayOf(1, n)))
        }
        val out = mel!!.forward(*args.toTypedArray()).toTuple()
        val melT = out[0].toTensor()
        val hid = backbone!!.forward(EValue.from(EtTensor.fromBlob(melT.dataAsFloatArray, melT.shape())))[0].toTensor()
        return head!!.forward(
            IValue.from(Tensor.fromBlob(hid.dataAsFloatArray, hid.shape())),
            IValue.from(sampleRate.toLong()),
            IValue.from(0.0),
            IValue.from(true),
        ).toTensor().dataAsFloatArray
    }

    @Synchronized
    override fun accentor(words: List<String>): Pair<Array<FloatArray>, Array<FloatArray>> {
        val out = accentor!!.forward(IValue.listFrom(*words.map { IValue.from(it) }.toTypedArray()))
        return accentorOut(out)
    }

    @Synchronized
    override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray): FloatArray {
        val model = checkNotNull(homo) { "Модель омографов не загружена" }
        if (ids.isEmpty()) return FloatArray(0)
        val b = ids.size
        val len = ids.maxOf { it.size }
        val flat = LongArray(b * len) { data.bertPad.toLong() }
        for ((i, row) in ids.withIndex()) row.copyInto(flat, i * len)
        val logits = model.forward(
            IValue.from(Tensor.fromBlob(flat, longArrayOf(b.toLong(), len.toLong()))),
            IValue.from(Tensor.fromBlob(starts, longArrayOf(b.toLong()))),
            IValue.from(Tensor.fromBlob(ends, longArrayOf(b.toLong()))),
        ).toTensor().dataAsFloatArray
        return FloatArray(b) { (1.0 / (1.0 + exp(-logits[it].toDouble()))).toFloat() }
    }

    fun release() {
        mel?.destroy(); head?.destroy(); backbone?.destroy(); accentor?.destroy(); homo?.destroy()
        mel = null; head = null; backbone = null; accentor = null; homo = null
    }

    private companion object {
        const val TAG = "SileroTtsEngine"

        fun softmaxRows(t: Tensor): Array<FloatArray> {
            val rows = t.shape()[0].toInt()
            val cols = t.shape()[1].toInt()
            val d = t.dataAsFloatArray
            return Array(rows) { r ->
                val row = FloatArray(cols) { d[r * cols + it] }
                val max = row.max()
                val e = row.map { exp((it - max).toDouble()) }
                val s = e.sum()
                FloatArray(cols) { (e[it] / s).toFloat() }
            }
        }

        fun accentorOut(out: IValue): Pair<Array<FloatArray>, Array<FloatArray>> {
            val parts = if (out.isTuple) out.toTuple() else arrayOf(out)
            val stress = softmaxRows(parts[0].toTensor())
            val yo = parts.getOrNull(1)?.takeIf { it.isTensor }?.let { softmaxRows(it.toTensor()) }
                ?: Array(stress.size) { FloatArray(0) }
            return stress to yo
        }

        val fastCores: Int by lazy {
            val freqs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                ?.mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
                ?: emptyList()
            val fast = freqs.minOrNull()?.let { m -> freqs.count { it > m } } ?: 0
            if (fast > 0) fast else Runtime.getRuntime().availableProcessors()
        }
    }
}
