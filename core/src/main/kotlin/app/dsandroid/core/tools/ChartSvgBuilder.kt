package app.dsandroid.core.tools

import kotlinx.serialization.json.*
import kotlin.math.*

data class ChartSeries(val name: String, val values: List<Double>)
object ChartSvgBuilder {
    fun build(type: String, title: String, labels: List<String>, series: List<ChartSeries>): String {
        require(type in listOf("bar","line","pie") && labels.size in 1..100 && series.size in 1..8)
        require(series.all { it.values.size == labels.size && it.values.all { v -> v.isFinite() && abs(v) <= 1e12 } })
        val colors=listOf("#3D63F5","#249F88","#E5A63D","#946BD6","#D56B6B","#4A9EC9","#8F993D","#B45E99")
        val result=StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"960\" height=\"600\" viewBox=\"0 0 960 600\" role=\"img\"><title>${xml(title.take(60))}</title><rect width=\"960\" height=\"600\" fill=\"white\"/><g font-family=\"sans-serif\" fill=\"#24304F\"><text x=\"40\" y=\"42\" font-size=\"24\">${xml(title.take(60))}</text>")
        if(type=="pie") {
            require(labels.size <= 20) { "Pie charts support up to 20 categories" }
            require(series.size==1 && series[0].values.all { it>=0 } && series[0].values.sum()>0)
            val values=series[0].values; var angle=-PI/2
            values.forEachIndexed { i,v -> val sweep=v/values.sum()*2*PI
                if(sweep >= 2*PI-1e-9) result.append("<circle cx=\"350\" cy=\"300\" r=\"200\" fill=\"${colors[i%8]}\"/>")
                else if(sweep>0) { val end=angle+sweep; result.append("<path d=\"M350 300 L${350+200*cos(angle)} ${300+200*sin(angle)} A200 200 0 ${if(sweep>PI) 1 else 0} 1 ${350+200*cos(end)} ${300+200*sin(end)} Z\" fill=\"${colors[i%8]}\"/>") }
                angle+=sweep
                result.append("<text x=\"600\" y=\"${100+i*22}\" fill=\"${colors[i%8]}\">${xml(labels[i].take(24))}: $v</text>")
            }
        } else {
            val low=min(0.0,series.flatMap { it.values }.minOrNull()!!); val high=max(0.0,series.flatMap { it.values }.maxOrNull()!!); val span=(high-low).takeIf { it>0 } ?: 1.0
            fun y(v:Double)=500-(v-low)/span*390
            val step=840.0/labels.size
            result.append("<path d=\"M70 100 V500 H920 M70 ${y(0.0)} H920\" fill=\"none\" stroke=\"#CCD3E0\"/>")
            series.forEachIndexed { s,values ->
                if (type == "line") {
                    val points = values.values.mapIndexed { i, value ->
                        val x = 70 + step * (i + 0.5)
                        result.append("<circle cx=\"$x\" cy=\"${y(value)}\" r=\"4\" fill=\"${colors[s]}\"/>")
                        "$x,${y(value)}"
                    }
                    result.append("<polyline fill=\"none\" stroke=\"${colors[s]}\" stroke-width=\"3\" points=\"${points.joinToString(" ")}\"/>")
                }
                else values.values.forEachIndexed { i,v -> val width=step*0.8/series.size; result.append("<rect x=\"${70+step*i+step*0.1+s*width}\" y=\"${min(y(v),y(0.0))}\" width=\"${width*0.9}\" height=\"${abs(y(v)-y(0.0))}\" fill=\"${colors[s]}\"/>") }
                result.append("<text x=\"${70+s*110}\" y=\"580\" fill=\"${colors[s]}\">${xml(values.name.take(12))}</text>")
            }
            labels.forEachIndexed { i,label -> result.append("<text x=\"${70+step*(i+0.5)}\" y=\"525\" text-anchor=\"middle\" font-size=\"12\">${xml(label.take((step / 7).toInt().coerceIn(1,24)))}</text>") }
            result.append("<text x=\"5\" y=\"110\">$high</text><text x=\"5\" y=\"500\">$low</text>")
        }
        return result.append("</g></svg>").toString()
    }
}
class CreateChartTool : Tool {
    override val name="create_chart"; override val description="Create an SVG bar, line or pie chart. Pie accepts one nonnegative series."
    override val risk=Risk.SAFE; override val kind=ToolKind.WRITE
    override val schema=objectSchema(mapOf("fileName" to stringSchema("Optional .svg filename"),"type" to stringSchema("bar, line or pie"),"title" to stringSchema("Title"),"labels" to arraySchema(stringSchema("Label")),"series" to arraySchema(objectSchema(mapOf("name" to stringSchema("Legend"),"values" to arraySchema(buildJsonObject { put("type","number") }))))),listOf("type","title","labels","series"))
    override suspend fun run(args:JsonObject,ctx:ToolContext):ToolResult {
        val series=args.arrayArg("series").map { val s=it.jsonObject; ChartSeries(s.stringArg("name"),s.arrayArg("values").map { v -> (v as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("Expected numeric value") }) }
        val svg=ChartSvgBuilder.build(args.stringArg("type"),args.stringArg("title"),args.arrayArg("labels").map { it.textValue() },series)
        return writeArtifact(ctx,args.outputName(".svg"),".svg","image/svg+xml",svg.toByteArray())
    }
}
