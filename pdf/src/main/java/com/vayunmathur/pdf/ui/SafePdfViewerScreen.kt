package com.vayunmathur.pdf.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.BottomAppBar
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CheckboxDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SmallFloatingActionButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.library.ocr.OcrEngine
import kotlinx.coroutines.withTimeoutOrNull
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconNote
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.IconBezier
import com.vayunmathur.library.ui.IconCallout
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconFormatUnderlined
import com.vayunmathur.library.ui.IconHighlight
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconLine
import com.vayunmathur.library.ui.IconPolyline
import com.vayunmathur.library.ui.IconRedact
import com.vayunmathur.library.ui.IconSelect
import com.vayunmathur.library.ui.IconSquiggly
import com.vayunmathur.library.ui.IconStrikethrough
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.IconTextTool
import com.vayunmathur.library.ui.IconShapeArrowFill
import com.vayunmathur.library.ui.IconShapeArrowOutline
import com.vayunmathur.library.ui.IconShapeDiamondFill
import com.vayunmathur.library.ui.IconShapeDiamondOutline
import com.vayunmathur.library.ui.IconShapeHexagonFill
import com.vayunmathur.library.ui.IconShapeHexagonOutline
import com.vayunmathur.library.ui.IconShapeOvalFill
import com.vayunmathur.library.ui.IconShapeOvalOutline
import com.vayunmathur.library.ui.IconShapePentagonFill
import com.vayunmathur.library.ui.IconShapePentagonOutline
import com.vayunmathur.library.ui.IconShapeRectFill
import com.vayunmathur.library.ui.IconShapeRectOutline
import com.vayunmathur.library.ui.IconShapeRoundRectFill
import com.vayunmathur.library.ui.IconShapeRoundRectOutline
import com.vayunmathur.library.ui.IconShapeStarFill
import com.vayunmathur.library.ui.IconShapeStarOutline
import com.vayunmathur.library.ui.IconShapeTriangleFill
import com.vayunmathur.library.ui.IconShapeTriangleOutline
import com.vayunmathur.pdf.util.SafeOutlineItem
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.BlendMode
import com.vayunmathur.pdf.util.PathOp
import com.vayunmathur.pdf.util.SafeAnnotation
import com.vayunmathur.pdf.util.SafeFormField
import com.vayunmathur.pdf.util.SafeLink
import com.vayunmathur.pdf.util.SafePdfDocument
import com.vayunmathur.pdf.util.PdfStateStore
import com.vayunmathur.pdf.util.SafePdfPage
import java.io.ByteArrayOutputStream

private sealed interface LoadState {
    data object Loading : LoadState
    /** [notPdf] true when the bytes aren't a PDF at all (e.g. an HTML download page), as
     *  opposed to a genuine PDF the parser can't handle. */
    data class Error(val notPdf: Boolean = false) : LoadState
    data class Loaded(val document: SafePdfDocument) : LoadState
}

/** Editing tools. */
private enum class EditTool { SELECT, TEXT, HIGHLIGHT, MARKUP, DRAW, SHAPE, LINE, POLYLINE, BEZIER, NOTE, CALLOUT, REDACT, IMAGE }

/** Text-markup variants for the [EditTool.MARKUP] tool. */
private enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }

private fun MarkupKind.icon(): @Composable (Color) -> Unit = when (this) {
    MarkupKind.HIGHLIGHT -> { t -> IconHighlight(tint = t) }
    MarkupKind.UNDERLINE -> { t -> IconFormatUnderlined(tint = t) }
    MarkupKind.STRIKEOUT -> { t -> IconStrikethrough(tint = t) }
    MarkupKind.SQUIGGLY -> { t -> IconSquiggly(tint = t) }
}

private fun MarkupKind.label(): String = when (this) {
    MarkupKind.HIGHLIGHT -> "Highlight"
    MarkupKind.UNDERLINE -> "Underline"
    MarkupKind.STRIKEOUT -> "Strikeout"
    MarkupKind.SQUIGGLY -> "Squiggly"
}

/** Closed-shape variants for the [EditTool.SHAPE] tool (dragged bounding box). */
private enum class ShapeKind {
    RECT_OUTLINE, RECT_FILL,
    ROUNDRECT_OUTLINE, ROUNDRECT_FILL,
    OVAL_OUTLINE, OVAL_FILL,
    TRIANGLE_OUTLINE, TRIANGLE_FILL,
    DIAMOND_OUTLINE, DIAMOND_FILL,
    PENTAGON_OUTLINE, PENTAGON_FILL,
    HEXAGON_OUTLINE, HEXAGON_FILL,
    STAR_OUTLINE, STAR_FILL,
    ARROW_OUTLINE, ARROW_FILL,
}

private enum class ShapeGeom { RECT, OVAL, POLYGON }

private val ShapeKind.isFill: Boolean get() = name.endsWith("_FILL")

private val ShapeKind.geom: ShapeGeom
    get() = when (this) {
        ShapeKind.RECT_OUTLINE, ShapeKind.RECT_FILL -> ShapeGeom.RECT
        ShapeKind.OVAL_OUTLINE, ShapeKind.OVAL_FILL -> ShapeGeom.OVAL
        else -> ShapeGeom.POLYGON
    }

/**
 * Vertices for [ShapeGeom.POLYGON] shapes in the unit square (x,y in 0..1,
 * y-down), to be scaled into the dragged bounding box. Empty for rect/oval.
 */
private fun ShapeKind.unitPolygon(): List<Offset> = when (this) {
    ShapeKind.TRIANGLE_OUTLINE, ShapeKind.TRIANGLE_FILL ->
        listOf(Offset(0.5f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    ShapeKind.DIAMOND_OUTLINE, ShapeKind.DIAMOND_FILL ->
        listOf(Offset(0.5f, 0f), Offset(1f, 0.5f), Offset(0.5f, 1f), Offset(0f, 0.5f))
    ShapeKind.PENTAGON_OUTLINE, ShapeKind.PENTAGON_FILL -> regularPolygonUnit(5)
    ShapeKind.HEXAGON_OUTLINE, ShapeKind.HEXAGON_FILL -> regularPolygonUnit(6)
    ShapeKind.STAR_OUTLINE, ShapeKind.STAR_FILL -> starUnit(5, 0.5f, 0.22f)
    ShapeKind.ARROW_OUTLINE, ShapeKind.ARROW_FILL -> listOf(
        Offset(0f, 0.3f), Offset(0.6f, 0.3f), Offset(0.6f, 0.08f), Offset(1f, 0.5f),
        Offset(0.6f, 0.92f), Offset(0.6f, 0.7f), Offset(0f, 0.7f),
    )
    ShapeKind.ROUNDRECT_OUTLINE, ShapeKind.ROUNDRECT_FILL -> roundRectUnit(0.2f, 5)
    else -> emptyList()
}

/** [n]-gon inscribed in the unit square, first vertex at top, y-down. */
private fun regularPolygonUnit(n: Int): List<Offset> = (0 until n).map { k ->
    val a = -Math.PI / 2 + 2 * Math.PI * k / n
    Offset(0.5f + 0.5f * kotlin.math.cos(a).toFloat(), 0.5f + 0.5f * kotlin.math.sin(a).toFloat())
}

/** [points]-pointed star with [outer]/[inner] radii, first point at top, y-down. */
private fun starUnit(points: Int, outer: Float, inner: Float): List<Offset> =
    (0 until points * 2).map { k ->
        val r = if (k % 2 == 0) outer else inner
        val a = -Math.PI / 2 + Math.PI * k / points
        Offset(0.5f + r * kotlin.math.cos(a).toFloat(), 0.5f + r * kotlin.math.sin(a).toFloat())
    }

/** Rounded rectangle perimeter as a polygon, corner [radius] in unit space with
 * [seg] segments per corner. */
private fun roundRectUnit(radius: Float, seg: Int): List<Offset> {
    val r = radius.coerceIn(0f, 0.5f)
    val pts = mutableListOf<Offset>()
    // Corner centers, and arc start angles (clockwise, y-down).
    val corners = listOf(
        Triple(1f - r, r, -Math.PI / 2),      // top-right
        Triple(1f - r, 1f - r, 0.0),          // bottom-right
        Triple(r, 1f - r, Math.PI / 2),       // bottom-left
        Triple(r, r, Math.PI),                // top-left
    )
    for ((cx, cy, start) in corners) {
        for (i in 0..seg) {
            val a = start + (Math.PI / 2) * i / seg
            pts += Offset(cx + r * kotlin.math.cos(a).toFloat(), cy + r * kotlin.math.sin(a).toFloat())
        }
    }
    return pts
}

/** Map a unit-square point into the screen-space bounding box [rect]. */
private fun mapUnit(u: Offset, rect: Rect): Offset =
    Offset(rect.left + u.x * rect.width, rect.top + u.y * rect.height)

/**
 * Smooth an open sequence of [points] into a flattened curve passing through
 * them (Catmull-Rom → cubic Bézier, sampled), for the Bézier tool.
 */
private fun flattenSmooth(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val out = mutableListOf(points.first())
    val steps = 16
    for (i in 0 until points.size - 1) {
        val p0 = points[if (i == 0) 0 else i - 1]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[if (i + 2 <= points.size - 1) i + 2 else points.size - 1]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val mt = 1f - t
            val x = mt * mt * mt * p1.x + 3 * mt * mt * t * c1.x + 3 * mt * t * t * c2.x + t * t * t * p2.x
            val y = mt * mt * mt * p1.y + 3 * mt * mt * t * c1.y + 3 * mt * t * t * c2.y + t * t * t * p2.y
            out += Offset(x, y)
        }
    }
    return out
}

private fun ShapeKind.icon(): @Composable (Color) -> Unit = when (this) {
    ShapeKind.RECT_OUTLINE -> { t -> IconShapeRectOutline(tint = t) }
    ShapeKind.RECT_FILL -> { t -> IconShapeRectFill(tint = t) }
    ShapeKind.ROUNDRECT_OUTLINE -> { t -> IconShapeRoundRectOutline(tint = t) }
    ShapeKind.ROUNDRECT_FILL -> { t -> IconShapeRoundRectFill(tint = t) }
    ShapeKind.OVAL_OUTLINE -> { t -> IconShapeOvalOutline(tint = t) }
    ShapeKind.OVAL_FILL -> { t -> IconShapeOvalFill(tint = t) }
    ShapeKind.TRIANGLE_OUTLINE -> { t -> IconShapeTriangleOutline(tint = t) }
    ShapeKind.TRIANGLE_FILL -> { t -> IconShapeTriangleFill(tint = t) }
    ShapeKind.DIAMOND_OUTLINE -> { t -> IconShapeDiamondOutline(tint = t) }
    ShapeKind.DIAMOND_FILL -> { t -> IconShapeDiamondFill(tint = t) }
    ShapeKind.PENTAGON_OUTLINE -> { t -> IconShapePentagonOutline(tint = t) }
    ShapeKind.PENTAGON_FILL -> { t -> IconShapePentagonFill(tint = t) }
    ShapeKind.HEXAGON_OUTLINE -> { t -> IconShapeHexagonOutline(tint = t) }
    ShapeKind.HEXAGON_FILL -> { t -> IconShapeHexagonFill(tint = t) }
    ShapeKind.STAR_OUTLINE -> { t -> IconShapeStarOutline(tint = t) }
    ShapeKind.STAR_FILL -> { t -> IconShapeStarFill(tint = t) }
    ShapeKind.ARROW_OUTLINE -> { t -> IconShapeArrowOutline(tint = t) }
    ShapeKind.ARROW_FILL -> { t -> IconShapeArrowFill(tint = t) }
}

private fun ShapeKind.label(): String {
    val base = when (geom) {
        ShapeGeom.RECT -> if (this == ShapeKind.ROUNDRECT_OUTLINE || this == ShapeKind.ROUNDRECT_FILL) "Rounded rectangle" else "Rectangle"
        ShapeGeom.OVAL -> "Ellipse"
        ShapeGeom.POLYGON -> when (this) {
            ShapeKind.ROUNDRECT_OUTLINE, ShapeKind.ROUNDRECT_FILL -> "Rounded rectangle"
            ShapeKind.TRIANGLE_OUTLINE, ShapeKind.TRIANGLE_FILL -> "Triangle"
            ShapeKind.DIAMOND_OUTLINE, ShapeKind.DIAMOND_FILL -> "Diamond"
            ShapeKind.PENTAGON_OUTLINE, ShapeKind.PENTAGON_FILL -> "Pentagon"
            ShapeKind.HEXAGON_OUTLINE, ShapeKind.HEXAGON_FILL -> "Hexagon"
            ShapeKind.STAR_OUTLINE, ShapeKind.STAR_FILL -> "Star"
            ShapeKind.ARROW_OUTLINE, ShapeKind.ARROW_FILL -> "Arrow"
            else -> "Shape"
        }
    }
    return if (isFill) "$base (filled)" else base
}

/** In-progress polyline / Bézier being built by tapping points. */
private data class PolyDraft(val page: Int, val points: List<Offset>, val bezier: Boolean)

/**
 * A reversible edit. ADDED (undo detaches), REMOVED (undo re-attaches), or MOVED
 * (undo restores [oldRect], redo restores [newRect]). Rects are page-space
 * [x0,y0,x1,y1].
 */
private enum class EditKind { ADDED, REMOVED, MOVED }

private data class EditAction(
    val page: Int,
    val annotId: Long,
    val kind: EditKind,
    val oldRect: List<Float>? = null,
    val newRect: List<Float>? = null,
)

/**
 * Pinch-zoom bounds. 1 is "page fills the viewport width"; below that the page is
 * drawn narrower than the screen so more of the document fits on it at once.
 */
private const val MIN_ZOOM = 0.5f
/// Minimum max-zoom (applies to normal-sized pages). Large pages raise this via
/// [maxZoomFor] so their fine detail can actually be inspected (issue #321
/// doesntzoomfully/doesntzoomfully2 were capped too low to read large pages).
private const val MAX_ZOOM = 6f
/// Absolute ceiling to bound pan math / memory regardless of page size.
private const val ABS_MAX_ZOOM = 40f
/// A large page may be zoomed until ~this many device pixels map to one PDF
/// point, past the fit-to-width baseline.
private const val TARGET_PX_PER_POINT = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val DOUBLE_TAP_ZOOM_THRESHOLD = 1.2f

/// Effective maximum zoom for a page [pageWidthPts] points wide shown in a
/// [viewportWidthPx]-wide viewport. At zoom 1 the page fills the viewport width,
/// so `Z` device-px per point = `Z * viewportWidthPx / pageWidthPts`; solving for
/// [TARGET_PX_PER_POINT] gives the cap. Clamped to [MAX_ZOOM]..[ABS_MAX_ZOOM] so
/// small pages keep a sane limit and huge pages can't overflow the pan math.
private fun maxZoomFor(pageWidthPts: Float, viewportWidthPx: Int): Float {
    if (pageWidthPts <= 0f || viewportWidthPx <= 0) return MAX_ZOOM
    val z = TARGET_PX_PER_POINT * pageWidthPts / viewportWidthPx
    return z.coerceIn(MAX_ZOOM, ABS_MAX_ZOOM)
}

/**
 * Clamp the zoom [pan] (screen-pixel translation) so the content, scaled by
 * [zoom] around its top edge, can't be dragged past the viewport ([size]) edges.
 * At zoom 1 the range is zero, so the page stays put. Because the pivot is the top
 * (not the centre), the vertical range runs from -(zoom-1)·height to 0 rather than
 * symmetrically about zero.
 */
private fun clampPan(pan: Offset, zoom: Float, size: IntSize): Offset {
    val maxX = (size.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (size.height * (zoom - 1f)).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, 0f))
}

/**
 * An in-progress inline text edit. [origin] is the top of the text box in page
 * space; [annotId] is set when editing an existing FreeText, null for a new one.
 */
private data class TextSession(
    val page: Int,
    val origin: Offset,
    val size: Float,
    val color: Int,
    val annotId: Long?,
    val value: TextFieldValue,
)

/**
 * Read-only + overlay-editing PDF viewer that never touches the system PDF
 * stack: pages are parsed in Rust ([SafePdfDocument]) and drawn from plain
 * primitives on a Compose [Canvas]. Editing (annotations, form filling) is
 * written back through lopdf and saved via SAF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafePdfViewerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharePdfLabel = stringResource(R.string.share_pdf)
    val pdfSavedMsg = stringResource(R.string.pdf_saved)
    val pdfSaveErrorMsg = stringResource(R.string.pdf_save_error)

    // Password handling for encrypted PDFs.
    var password by remember(uri) { mutableStateOf<String?>(null) }
    var needsPassword by remember(uri) { mutableStateOf(false) }
    var pwError by remember(uri) { mutableStateOf(false) }

    val loadState by produceState<LoadState>(LoadState.Loading, uri, password) {
        value = LoadState.Loading
        val doc = SafePdfDocument.open(context, uri, password)
        value = if (doc != null) {
            needsPassword = false
            LoadState.Loaded(doc)
        } else {
            when (SafePdfDocument.passwordState(context, uri)) {
                1 -> { needsPassword = true; pwError = password != null; LoadState.Loading }
                else -> LoadState.Error(notPdf = !SafePdfDocument.looksLikePdf(context, uri))
            }
        }
    }
    val document = (loadState as? LoadState.Loaded)?.document
    DisposableEffect(document) { onDispose { document?.close() } }

    if (needsPassword) {
        var pwInput by remember { mutableStateOf("") }
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { onBack() },
            title = { Text(stringResource(R.string.password_required)) },
            text = {
                Column {
                    if (pwError) Text(stringResource(R.string.incorrect_password), color = MaterialTheme.colorScheme.error)
                    TextField(
                        value = pwInput,
                        onValueChange = { pwInput = it },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        placeholder = { Text(stringResource(R.string.password)) },
                    )
                }
            },
            confirmButton = { TextButton({ needsPassword = false; password = pwInput }) { Text(stringResource(R.string.open)) } },
            dismissButton = { TextButton({ onBack() }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    // Prebuild the search index in the background so the first query is instant.
    LaunchedEffect(document) { document?.prewarmSearch() }

    // On-device OCR engine (PP-OCRv5 / ncnn), shared across all pages so that
    // scanned PDFs with no embedded text layer still become selectable. Lazy:
    // native models only load on the first page that actually needs OCR.
    val ocrEngine = remember { OcrEngine(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { ocrEngine.close() } }

    var editMode by remember { mutableStateOf(false) }
    var showSaveMenu by remember { mutableStateOf(false) }
    // Set once any edit is made; keeps the Save control visible thereafter.
    var dirty by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(EditTool.SELECT) }
    var shape by remember { mutableStateOf(ShapeKind.RECT_OUTLINE) }
    var markup by remember { mutableStateOf(MarkupKind.HIGHLIGHT) }
    // Pending note/callout awaiting text entry: page + point(s).
    var pendingNote by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var pendingCallout by remember { mutableStateOf<Triple<Int, Offset, Offset>?>(null) }
    // In-progress polyline/Bézier (built by tapping points; committed via the check button).
    var polyDraft by remember { mutableStateOf<PolyDraft?>(null) }
    var color by remember { mutableStateOf(Color.Red) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var strokeWidth by remember { mutableFloatStateOf(2f) }
    var showStyle by remember { mutableStateOf(false) }
    // Undo/redo of annotation add/remove/move (backed by native ops).
    val undoStack = remember { mutableStateListOf<EditAction>() }
    val redoStack = remember { mutableStateListOf<EditAction>() }
    // Set by non-undoable edits (forms, flatten, redactions) so Save still shows.
    var nonUndoDirty by remember { mutableStateOf(false) }
    var pageCount by remember(document) { mutableIntStateOf(document?.pageCount ?: 0) }
    var pageMgrVersion by remember { mutableIntStateOf(0) }
    // Per-page render version: bumping one page's entry re-renders ONLY that page,
    // so an edit doesn't force every visible page to re-decode.
    val pageVersions = remember { mutableStateMapOf<Int, Int>() }
    var selected by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    // Re-render the edited page and record that an edit was made.
    val markEdited: (Int) -> Unit = { page ->
        pageVersions[page] = (pageVersions[page] ?: 0) + 1
        dirty = true
    }
    // Register a freshly created annotation for undo.
    val registerCreated: (Int, Long) -> Unit = { page, id ->
        if (id != 0L) {
            undoStack.add(EditAction(page, id, EditKind.ADDED))
            redoStack.clear()
        }
    }
    val undo: () -> Unit = {
        val a = undoStack.removeLastOrNull()
        val doc = document
        if (a != null && doc != null) {
            scope.launch {
                when (a.kind) {
                    EditKind.ADDED -> doc.detachAnnotation(a.page, a.annotId)
                    EditKind.REMOVED -> doc.reattachAnnotation(a.page, a.annotId)
                    EditKind.MOVED -> a.oldRect?.let {
                        doc.moveAnnotation(a.page, a.annotId, it[0], it[1], it[2], it[3])
                    }
                }
                redoStack.add(a); selected = null; markEdited(a.page)
            }
        }
    }
    val redo: () -> Unit = {
        val a = redoStack.removeLastOrNull()
        val doc = document
        if (a != null && doc != null) {
            scope.launch {
                when (a.kind) {
                    EditKind.ADDED -> doc.reattachAnnotation(a.page, a.annotId)
                    EditKind.REMOVED -> doc.detachAnnotation(a.page, a.annotId)
                    EditKind.MOVED -> a.newRect?.let {
                        doc.moveAnnotation(a.page, a.annotId, it[0], it[1], it[2], it[3])
                    }
                }
                undoStack.add(a); selected = null; markEdited(a.page)
            }
        }
    }
    // Record an annotation move for undo/redo.
    val registerMoved: (Int, Long, List<Float>, List<Float>) -> Unit = { page, id, oldR, newR ->
        undoStack.add(EditAction(page, id, EditKind.MOVED, oldR, newR))
        redoStack.clear()
    }

    // Search state.
    val listState = rememberLazyListState()
    // Effective annotation color including the opacity slider's alpha.
    val drawColor = color.copy(alpha = opacity)
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    // Matches as (pageIndex, page-space rect).
    var matches by remember { mutableStateOf<List<Pair<Int, Rect>>>(emptyList()) }
    var matchIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, document, searching, caseSensitive) {
        val doc = document
        if (doc == null || !searching || query.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        matches = doc.search(query, caseSensitive).map { m ->
            m.page to Rect(m.x0, m.y0, m.x1, m.y1)
        }
        matchIndex = 0
    }

    LaunchedEffect(matchIndex, matches) {
        matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it.first) }
    }

    // Outline (bookmarks) + navigation drawer.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val outline by produceState(emptyList<SafeOutlineItem>(), document) {
        value = document?.outline() ?: emptyList()
    }
    // Show the Apply-redactions action only while redaction annotations exist.
    val hasRedactions by produceState(false, undoStack.size, redoStack.size, pageMgrVersion) {
        value = document?.hasRedactions() ?: false
    }

    // Restore last-read page, then persist the first-visible page as it changes.
    LaunchedEffect(document) {
        if (document != null) {
            val p = PdfStateStore.restoreSafePage(context, uri)
            if (p > 0) runCatching { listState.scrollToItem(p) }
        }
    }
    LaunchedEffect(document) {
        if (document == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { PdfStateStore.saveSafePage(context, uri, it) }
    }

    // Pinch-to-zoom + pan (two-finger); single-finger still scrolls.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Widest page seen so far (PDF points), used to raise the zoom cap for large
    // pages so their detail can be inspected. Monotonic across lazily-loaded pages.
    var maxPageWidthPts by remember { mutableFloatStateOf(0f) }
    // True while ≥2 fingers are down. The LazyColumn's scroll is a descendant of
    // the transformable, so it can otherwise claim a two-finger drag as a scroll
    // before the pinch is recognized; disabling scroll during multi-touch makes a
    // pinch always zoom.
    var multiTouch by remember { mutableStateOf(false) }
    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val zoomOld = zoom
        val maxZoom = maxZoomFor(maxPageWidthPts, viewportSize.width)
        zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, maxZoom)
        // Keep the content point under the gesture centroid fixed while zooming, then
        // apply the two-finger drag. `transformable` sits above the graphicsLayer in the
        // modifier chain, so centroid/panChange already arrive in viewport pixels, and
        // the layer pivots on its top-centre.
        val origin = Offset(viewportSize.width / 2f, 0f)
        val pivoted = pan + (centroid - origin - pan) * (1f - zoom / zoomOld)
        // Below zoom 1 the content already fits the viewport exactly, so there is
        // nothing to pan to.
        pan = if (zoom > 1f) clampPan(pivoted + panChange, zoom, viewportSize) else Offset.Zero
    }

    // Double-tap to zoom in/out with animation centered on the tap.
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(pan)
    val latestViewport by rememberUpdatedState(viewportSize)
    val latestEditMode by rememberUpdatedState(editMode)
    val latestTool by rememberUpdatedState(tool)

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) runCatching { searchFocus.requestFocus() }
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            searching -> { searching = false; query = "" }
            editMode -> { editMode = false; selected = null }
            else -> onBack()
        }
    }

    // Inline text-editing session (draws a live text field on the page).
    var textSession by remember { mutableStateOf<TextSession?>(null) }
    // Image-stamp target awaiting a picked image.
    var imageTarget by remember { mutableStateOf<Pair<Int, Offset>?>(null) }

    // Persist a finished text session as a FreeText annotation.
    val commitText: (TextSession?) -> Unit = commit@{ s ->
        if (s == null) return@commit
        val doc = document ?: return@commit
        val txt = s.value.text.trim()
        scope.launch {
            val newId = when {
                s.annotId != null && txt.isEmpty() -> { doc.deleteAnnotation(s.page, s.annotId); 0L }
                s.annotId != null -> { doc.editText(s.page, s.annotId, txt); 0L }
                txt.isNotEmpty() -> doc.addText(
                    s.page, s.origin.x, s.origin.y - s.size * 1.3f, s.origin.x + 220f, s.origin.y,
                    s.color, s.size, txt,
                )
                else -> return@launch
            }
            registerCreated(s.page, newId)
            markEdited(s.page)
        }
    }

    // Finish the in-progress polyline/Bézier: flatten (Bézier) and store as an
    // open PolyLine annotation. Needs >= 2 points; otherwise just discards.
    val commitPoly: () -> Unit = {
        val d = polyDraft
        val doc = document
        if (d != null && doc != null && d.points.size >= 2) {
            val pts = if (d.bezier) flattenSmooth(d.points) else d.points
            val flat = FloatArray(pts.size * 2)
            pts.forEachIndexed { i, p -> flat[i * 2] = p.x; flat[i * 2 + 1] = p.y }
            scope.launch {
                val id = doc.addPoly(d.page, flat, drawColor.toArgb(), strokeWidth, fill = false, closed = false)
                registerCreated(d.page, id)
                markEdited(d.page)
            }
        }
        polyDraft = null
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        val doc = document
        if (outUri != null && doc != null) {
            scope.launch {
                val bytes = doc.save()
                if (bytes != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) }
                    }
                }
            }
        }
    }

    var showEncrypt by remember { mutableStateOf(false) }
    var pendingEncryptPw by remember { mutableStateOf<String?>(null) }
    val encryptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        val doc = document
        val pw = pendingEncryptPw
        pendingEncryptPw = null
        if (outUri != null && doc != null && pw != null) scope.launch {
            val bytes = doc.saveEncrypted(pw, "")
            if (bytes != null) withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) } }
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imgUri ->
        val doc = document
        val target = imageTarget
        imageTarget = null
        if (imgUri != null && doc != null && target != null) {
            scope.launch {
                val jpeg = readAsJpeg(context, imgUri) ?: return@launch
                val (index, pt) = target
                // Default 150pt-wide stamp preserving aspect ratio.
                val w = 150f
                val h = w * jpeg.height / jpeg.width.coerceAtLeast(1)
                doc.addImageStamp(
                    index, pt.x, pt.y - h, pt.x + w, pt.y, jpeg.width, jpeg.height, jpeg.bytes
                ).also { registerCreated(index, it) }
                markEdited(index)
            }
        }
    }

    val shareAction = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ExternalIntents.launch(context, Intent.createChooser(intent, sharePdfLabel))
    }

    // "Save": overwrite the original file in place.
    val saveInPlace: () -> Unit = {
        val doc = document
        if (doc != null) {
            scope.launch {
                val bytes = doc.save()
                val ok = bytes != null && runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                }.getOrDefault(false)
                AppMessages.show(if (ok) pdfSavedMsg else pdfSaveErrorMsg)
            }
        }
    }

    PdfOutlineDrawer(
        outline = outline,
        drawerState = drawerState,
        onSelectPage = { page ->
            scope.launch {
                if (page >= 0) listState.animateScrollToItem(page)
                drawerState.close()
            }
        },
    ) {
    AppScaffold(
        modifier = Modifier.fillMaxSize(),
        title = {
                    if (searching) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f).focusRequester(searchFocus),
                                placeholder = { Text(stringResource(R.string.search_label)) },
                                singleLine = true,
                            )
                            Checkbox(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                            Text(stringResource(R.string.aa), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconNavigation { searching = false; query = "" }
                    } else if (outline.isNotEmpty()) {
                        IconButton({ scope.launch { drawerState.open() } }) { IconMenu() }
                    } else {
                        IconNavigation { onBack() }
                    }
                },
                actions = {
                    if (searching) {
                        if (matches.isNotEmpty()) {
                            Text(
                                "${matchIndex + 1}/${matches.size}",
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            IconButton({ if (matchIndex > 0) matchIndex-- }) {
                                IconKeyboardArrowUp()
                            }
                            IconButton({ if (matchIndex < matches.size - 1) matchIndex++ }) {
                                IconKeyboardArrowDown()
                            }
                        }
                    } else {
                        if (!editMode) {
                            IconButton({ searching = true }) { IconSearch() }
                            IconButton({ showEncrypt = true }) {
                                IconLock()
                            }
                        }
                        if (editMode) {
                            IconButton({ undo() }, enabled = undoStack.isNotEmpty()) {
                                IconUndo()
                            }
                            IconButton({ redo() }, enabled = redoStack.isNotEmpty()) {
                                IconRedo()
                            }
                        }
                        if (hasRedactions) {
                            IconButton({
                                val doc = document
                                if (doc != null) scope.launch {
                                    doc.applyRedactions(); pageMgrVersion++; nonUndoDirty = true
                                }
                            }) {
                                IconRedact()
                            }
                        }
                        IconButton({
                            commitText(textSession); textSession = null
                            commitPoly()
                            editMode = !editMode; selected = null
                        }) {
                            if (editMode) IconVisible() else IconEdit()
                        }
                        if (undoStack.isNotEmpty() || nonUndoDirty) {
                            Box {
                                IconButton({ showSaveMenu = true }) { IconSave() }
                                DropdownMenu(
                                    expanded = showSaveMenu,
                                    onDismissRequest = { showSaveMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.pdf_save)) },
                                        onClick = { showSaveMenu = false; saveInPlace() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.save_as_u2026)) },
                                        onClick = {
                                            showSaveMenu = false
                                            saveLauncher.launch(uri.lastPathSegment ?: "edited.pdf")
                                        },
                                    )
                                }
                            }
                        } else {
                            IconButton({ shareAction() }) { IconShare() }
                        }
                    }
                },
        bottomBar = {
            if (editMode) {
                EditToolbar(
                    tool = tool,
                    onTool = {
                        // Leaving the multi-point tools finalizes any in-progress draft.
                        if (it != EditTool.POLYLINE && it != EditTool.BEZIER) commitPoly()
                        tool = it; selected = null
                    },
                    shape = shape,
                    onShape = { shape = it; tool = EditTool.SHAPE; selected = null; commitPoly() },
                    markup = markup,
                    onMarkup = { markup = it; tool = EditTool.MARKUP; selected = null; commitPoly() },
                    color = color,
                    onColor = { color = it },
                    onStyle = { showStyle = true },
                    canDelete = selected != null,
                    onDelete = {
                        val sel = selected
                        val doc = document
                        if (sel != null && doc != null) {
                            scope.launch {
                                // Detach (not delete) so it can be undone.
                                doc.detachAnnotation(sel.first, sel.second)
                                undoStack.add(EditAction(sel.first, sel.second, EditKind.REMOVED))
                                redoStack.clear()
                                selected = null
                                markEdited(sel.first)
                            }
                        }
                    },
                    onDuplicate = {
                        val sel = selected
                        val doc = document
                        if (sel != null && doc != null) {
                            scope.launch {
                                val newId = doc.duplicateAnnotation(sel.first, sel.second, 14f, -14f)
                                registerCreated(sel.first, newId)
                                if (newId != 0L) selected = sel.first to newId
                                markEdited(sel.first)
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            val draft = polyDraft
            if (editMode && draft != null) {
                Column {
                    SmallFloatingActionButton(onClick = { polyDraft = null }) {
                        IconClose()
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    com.vayunmathur.library.ui.FloatingActionButton(
                        onClick = { commitPoly() },
                    ) { IconCheck() }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                // Track finger count in the Initial pass (before the LazyColumn's
                // scroll reacts in the Main pass) so multi-touch disables scroll.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            multiTouch = e.changes.count { it.pressed } >= 2
                        }
                    }
                }
                // Double-tap to zoom in/out, centered on the tap with animation.
                // Disabled in edit mode (except SELECT) to avoid conflicting with annotation taps.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (latestEditMode && latestTool != EditTool.SELECT) return@detectTapGestures
                            val vp = latestViewport
                            if (vp == IntSize.Zero) return@detectTapGestures
                            val targetZoom = if (latestZoom < DOUBLE_TAP_ZOOM_THRESHOLD) DOUBLE_TAP_ZOOM else 1f
                            val origin = Offset(vp.width / 2f, 0f)
                            // Where the tap point should end up: zoomed-in it's offset from center.
                            val targetPan = if (targetZoom <= 1f) {
                                Offset.Zero
                            } else {
                                val zoomDelta = targetZoom / latestZoom.coerceAtLeast(0.001f)
                                val pivoted = latestPan + (tapOffset - origin - latestPan) * (1f - zoomDelta)
                                clampPan(pivoted, targetZoom, vp)
                            }
                            scope.launch {
                                val startZoom = latestZoom
                                val startPan = latestPan
                                val anim = Animatable(0f)
                                anim.animateTo(1f, animationSpec = tween(durationMillis = 250)) {
                                    val t = value
                                    zoom = startZoom + (targetZoom - startZoom) * t
                                    pan = Offset(
                                        startPan.x + (targetPan.x - startPan.x) * t,
                                        startPan.y + (targetPan.y - startPan.y) * t,
                                    )
                                }
                                zoom = targetZoom
                                pan = targetPan
                            }
                        }
                    )
                }
                // Above the graphicsLayer so gesture coordinates are plain viewport pixels.
                .transformable(transformState, enabled = !editMode || tool == EditTool.SELECT)
                // Zooming below 1 draws the page narrower than the screen. Lay the content
                // out taller by 1/zoom so that, scaled back down, it still fills the
                // viewport height — otherwise it would shrink to a band with blank space
                // above and below. The layout width stays the viewport width, so pages keep
                // rasterizing at full resolution and are only downscaled when drawn.
                .layout { measurable, constraints ->
                    val h = if (zoom < 1f && constraints.hasBoundedHeight) {
                        (constraints.maxHeight / zoom).roundToInt()
                    } else {
                        constraints.maxHeight
                    }
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                    // Pivot on the top edge: keeps the first visible page anchored to the top
                    // of the screen across zoom changes, and makes the taller-than-viewport
                    // layout above scale back to exactly the viewport height.
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) {
            when (val state = loadState) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LoadState.Error -> Text(
                    text = stringResource(
                        if (state.notPdf) R.string.safe_pdf_not_a_pdf else R.string.safe_pdf_error
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is LoadState.Loaded -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    userScrollEnabled = !multiTouch,
                ) {
                    items((0 until pageCount).toList()) { index ->
                        val pageHighlights = matches.filter { it.first == index }.map { it.second }
                        val current = matches.getOrNull(matchIndex)
                        val currentHighlight = if (current?.first == index) current.second else null
                        SafePdfPageItem(
                            document = state.document,
                            index = index,
                            version = (pageVersions[index] ?: 0) + pageMgrVersion,
                            ocr = ocrEngine,
                            editMode = editMode,
                            tool = tool,
                            shape = shape,
                            markup = markup,
                            color = drawColor,
                            strokeWidth = strokeWidth,
                            selected = selected?.takeIf { it.first == index }?.second,
                            highlights = pageHighlights,
                            currentHighlight = currentHighlight,
                            scope = scope,
                            onSelect = { annotId -> selected = annotId?.let { index to it } },
                            onEdited = { markEdited(index) },
                            onCreated = { id -> registerCreated(index, id) },
                            onMoved = { id, oldR, newR -> registerMoved(index, id, oldR, newR) },
                            onFormEdited = { markEdited(index); nonUndoDirty = true },
                            onPageWidth = { w -> if (w > maxPageWidthPts) maxPageWidthPts = w },
                            onLinkPage = { p -> scope.launch { listState.animateScrollToItem(p.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) } },
                            textSession = textSession?.takeIf { it.page == index },
                            onStartText = { s -> commitText(textSession); textSession = s },
                            onTextChange = { v -> textSession = textSession?.copy(value = v) },
                            onCommitText = { commitText(textSession); textSession = null },
                            onRequestImage = { pt -> imageTarget = index to pt; imageLauncher.launch("image/*") },
                            onRequestNote = { pt -> pendingNote = index to pt },
                            onRequestCallout = { a, b -> pendingCallout = Triple(index, a, b) },
                            polyDraft = polyDraft?.takeIf { it.page == index },
                            onAddPolyPoint = { pt ->
                                val d = polyDraft
                                polyDraft = when {
                                    d == null -> PolyDraft(index, listOf(pt), tool == EditTool.BEZIER)
                                    d.page == index -> d.copy(points = d.points + pt)
                                    else -> d // ignore taps on other pages while drafting
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingNote?.let { (page, pt) ->
        var noteText by remember { mutableStateOf("") }
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { pendingNote = null },
            title = { Text(stringResource(R.string.sticky_note)) },
            text = {
                TextField(value = noteText, onValueChange = { noteText = it }, placeholder = { Text(stringResource(R.string.note_text)) })
            },
            confirmButton = {
                TextButton({
                    val doc = document
                    if (doc != null) scope.launch {
                        val id = doc.addNote(page, pt.x, pt.y, drawColor.toArgb(), noteText)
                        registerCreated(page, id); markEdited(page)
                    }
                    pendingNote = null
                }) { Text(stringResource(UiR.string.add)) }
            },
            dismissButton = { TextButton({ pendingNote = null }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    pendingCallout?.let { (page, a, b) ->
        var calloutText by remember { mutableStateOf("Text") }
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { pendingCallout = null },
            title = { Text(stringResource(R.string.callout)) },
            text = { TextField(value = calloutText, onValueChange = { calloutText = it }) },
            confirmButton = {
                TextButton({
                    val doc = document
                    if (doc != null) scope.launch {
                        val id = doc.addCallout(page, a.x, a.y, b.x, b.y, drawColor.toArgb(), 14f, calloutText)
                        registerCreated(page, id); markEdited(page)
                    }
                    pendingCallout = null
                }) { Text(stringResource(UiR.string.add)) }
            },
            dismissButton = { TextButton({ pendingCallout = null }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    if (showStyle) {
        StyleDialog(
            color = color,
            onColor = { color = it },
            opacity = opacity,
            onOpacity = { opacity = it },
            strokeWidth = strokeWidth,
            onWidth = { strokeWidth = it },
            onDismiss = { showStyle = false },
        )
    }

    if (showEncrypt) {
        var pw by remember { mutableStateOf("") }
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { showEncrypt = false },
            title = { Text(stringResource(R.string.encrypt_with_password)) },
            text = {
                TextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    placeholder = { Text(stringResource(R.string.password)) },
                )
            },
            confirmButton = {
                TextButton({
                    showEncrypt = false
                    if (pw.isNotEmpty()) { pendingEncryptPw = pw; encryptLauncher.launch("encrypted.pdf") }
                }) { Text(stringResource(R.string.save_encrypted)) }
            },
            dismissButton = { TextButton({ showEncrypt = false }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
    }
}

/**
 * The bookmarks drawer wrapped around the reader.
 *
 * Split out of [SafePdfViewerScreen] because it needs nothing from the native document —
 * just the already-read [outline] — so a `@Preview` can render it. See
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfOutlineDrawer(
    outline: List<SafeOutlineItem>,
    drawerState: DrawerState,
    onSelectPage: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.82f)) {
                Text(stringResource(R.string.outline),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(outline) { entry ->
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPage(entry.page) }
                                .padding(
                                    start = (16 + entry.level * 14).dp,
                                    end = 16.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                        )
                    }
                }
            }
        },
        content = content,
    )
}

/**
 * One page drawn at fit-to-width scale on its white "paper", with [overlays] stacked on top
 * once the page is known — they get the measured canvas width/height and the page-space to
 * pixel [scale] they need to position themselves.
 *
 * Takes a decoded [SafePdfPage] rather than a document handle, so a `@Preview` can render a
 * hand-built page (the primitives are plain data; only producing them needs the native
 * renderer).
 */
@Composable
fun SafePdfPageCanvas(
    page: SafePdfPage?,
    modifier: Modifier = Modifier,
    overlays: @Composable BoxWithConstraintsScope.(cw: Float, ch: Float, scale: Float) -> Unit = { _, _, _ -> },
) {
    val ratio = if (page != null && page.height > 0f) page.width / page.height else 612f / 792f

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(4.dp)
            .aspectRatio(ratio)
            .background(if (page == null) MaterialTheme.colorScheme.surfaceVariant else Color.White)
            .clipToBounds()
    ) {
        if (page == null || page.width <= 0f) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val decoded = page

        // Render the (static) page into its own graphics layer so that overlay
        // redraws while drawing/dragging don't replay every page primitive.
        Canvas(Modifier.fillMaxSize().graphicsLayer { clip = true }) { drawSafePage(decoded) }

        val cw = constraints.maxWidth.toFloat()
        overlays(cw, constraints.maxHeight.toFloat(), cw / decoded.width)
    }
}

@Composable
private fun SafePdfPageItem(
    document: SafePdfDocument,
    index: Int,
    version: Int,
    ocr: OcrEngine?,
    editMode: Boolean,
    tool: EditTool,
    shape: ShapeKind,
    markup: MarkupKind,
    color: Color,
    strokeWidth: Float,
    selected: Long?,
    highlights: List<Rect>,
    currentHighlight: Rect?,
    scope: CoroutineScope,
    onSelect: (Long?) -> Unit,
    onEdited: () -> Unit,
    onCreated: (Long) -> Unit,
    onMoved: (Long, List<Float>, List<Float>) -> Unit,
    onFormEdited: () -> Unit,
    onPageWidth: (Float) -> Unit = {},
    textSession: TextSession?,
    onStartText: (TextSession) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onCommitText: () -> Unit,
    onRequestImage: (Offset) -> Unit,
    onRequestNote: (Offset) -> Unit,
    onRequestCallout: (Offset, Offset) -> Unit,
    polyDraft: PolyDraft?,
    onAddPolyPoint: (Offset) -> Unit,
    onLinkPage: (Int) -> Unit,
) {
    val page by produceState<SafePdfPage?>(null, document, index, version) {
        value = document.renderPage(index)
    }
    val annotations by produceState(emptyList<SafeAnnotation>(), document, index, version) {
        value = if (editMode) document.annotations(index) else emptyList()
    }
    val formFields by produceState(emptyList<SafeFormField>(), document, index, version) {
        value = if (editMode) document.formFields(index) else emptyList()
    }
    val links by produceState(emptyList<SafeLink>(), document, index, version, editMode) {
        value = if (!editMode) document.links(index) else emptyList()
    }

    val current = page

    // Report this page's width so the viewer can raise the zoom cap for large pages.
    LaunchedEffect(current?.width) {
        current?.width?.let { if (it > 0f) onPageWidth(it) }
    }

    SafePdfPageCanvas(current) { cw, ch, scale ->
        // Non-null inside the overlay slot: SafePdfPageCanvas only invokes it once the page
        // has been decoded.
        val decoded = current!!

        fun toPage(o: Offset) = Offset(o.x / scale, (ch - o.y) / scale)

        if (highlights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for (r in highlights) {
                    val isCurrent = r == currentHighlight
                    drawRect(
                        color = if (isCurrent) Color(0xAAFF9800) else Color(0x66FFEB3B),
                        topLeft = Offset(r.left * scale, ch - r.top * scale),
                        size = Size((r.right - r.left) * scale, (r.top - r.bottom) * scale),
                    )
                }
            }
        }

        if (!editMode) {
            NonEditOverlay(
                page = decoded,
                links = links,
                cw = cw,
                ch = ch,
                scale = scale,
                ocr = ocr,
                onLinkPage = onLinkPage,
            )
        }

        if (editMode) {
            EditOverlay(
                page = decoded,
                annotations = annotations,
                selected = selected,
                tool = tool,
                shape = shape,
                markup = markup,
                color = color,
                strokeWidth = strokeWidth,
                cw = cw,
                ch = ch,
                scale = scale,
                toPage = ::toPage,
                document = document,
                index = index,
                scope = scope,
                onSelect = onSelect,
                onEdited = onEdited,
                onCreated = onCreated,
                onMoved = onMoved,
                onStartText = onStartText,
                onRequestImage = onRequestImage,
                onRequestNote = onRequestNote,
                onRequestCallout = onRequestCallout,
                polyDraft = polyDraft,
                onAddPolyPoint = onAddPolyPoint,
            )
            FormFieldOverlay(
                fields = formFields,
                ch = ch,
                scale = scale,
                document = document,
                index = index,
                scope = scope,
                onEdited = onFormEdited,
            )

            // Inline text editing: a live text field drawn on the page.
            if (textSession != null) {
                val density = LocalDensity.current
                val focus = remember(textSession.annotId, textSession.origin) { FocusRequester() }
                LaunchedEffect(textSession.annotId, textSession.origin) { focus.requestFocus() }
                val leftDp = with(density) { (textSession.origin.x * scale).toDp() }
                val topDp = with(density) { (ch - textSession.origin.y * scale).toDp() }
                BasicTextField(
                    value = textSession.value,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .widthIn(min = 48.dp)
                        .focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused) onCommitText() }
                        .background(Color(0x33448AFF)),
                    textStyle = TextStyle(
                        color = Color(textSession.color),
                        fontSize = with(density) { (textSession.size * scale).toSp() },
                    ),
                    cursorBrush = SolidColor(Color(textSession.color)),
                )
            }
        }
    }
}

@Composable
private fun NonEditOverlay(
    page: SafePdfPage,
    links: List<SafeLink>,
    cw: Float,
    ch: Float,
    scale: Float,
    ocr: OcrEngine?,
    onLinkPage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Text selection underneath so link Boxes get hit-test priority.
    TextSelectionLayer(page = page, ch = ch, scale = scale, ocr = ocr)
    for (link in links) {
        val leftDp = with(density) { (link.x0 * scale).toDp() }
        val topDp = with(density) { (ch - link.y1 * scale).toDp() }
        // A link rect can sit at/above the page top or off the left edge, making these
        // negative; use offset (which permits it) rather than padding (which throws).
        // Coerce the size non-negative in case a malformed link has x1<x0 or y1<y0.
        val wDp = with(density) { ((link.x1 - link.x0) * scale).toDp() }.coerceAtLeast(0.dp)
        val hDp = with(density) { ((link.y1 - link.y0) * scale).toDp() }.coerceAtLeast(0.dp)
        Box(
            Modifier
                .offset(x = leftDp, y = topDp)
                .size(wDp, hDp)
                .clickable {
                    if (link.uri.isNotEmpty()) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, link.uri.toUri())
                            )
                        }
                    } else if (link.destPage >= 0) {
                        onLinkPage(link.destPage)
                    }
                },
        )
    }
}

/** A single selectable glyph in reading order, with its on-screen rect - stores String for ligatures fi etc. */
private data class SelGlyph(val ch: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Build the substitute [android.graphics.Typeface] for a text primitive. The
 * embedded PDF font isn't rasterized, so we pick a system typeface matching the
 * generic family (0 sans-serif, 1 serif, 2 monospace) recovered by the Rust core
 * from BaseFont / FontDescriptor, then synthesize bold/italic.
 */
private fun pdfTypeface(family: Int, bold: Boolean, italic: Boolean): android.graphics.Typeface {
    val base = when (family) {
        1 -> android.graphics.Typeface.SERIF
        2 -> android.graphics.Typeface.MONOSPACE
        else -> android.graphics.Typeface.SANS_SERIF
    }
    val style = when {
        bold && italic -> android.graphics.Typeface.BOLD_ITALIC
        bold -> android.graphics.Typeface.BOLD
        italic -> android.graphics.Typeface.ITALIC
        else -> android.graphics.Typeface.NORMAL
    }
    return android.graphics.Typeface.create(base, style)
}

/** A glyph plus its page-space ordering keys (baseline-Y desc, X asc) for merge+sort. */
private data class OrderedGlyph(val orderY: Float, val orderX: Float, val glyph: SelGlyph)

/** Pages with fewer than this many embedded glyphs are treated as scanned → OCR. */
private const val MIN_EMBEDDED_GLYPHS_FOR_TEXT = 6

/** Max distance (page px) a long-press may be from a glyph to start a selection. */
private const val SELECT_HIT_PX = 80f

/**
 * Build ordered selectable glyphs from a page's embedded Text primitives. Uses
 * accurate glyph advances via Text.advance and Paint.measureText for ligatures /
 * multi-char runs, with bold/italic so measured widths match the painted glyphs.
 */
private fun buildEmbeddedGlyphs(page: SafePdfPage, ch: Float, scale: Float): List<OrderedGlyph> {
    val list = ArrayList<OrderedGlyph>()
    val tmpPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
        isLinearText = true
    }
    for (prim in page.primitives) {
        if (prim !is PdfPrimitive.Text || prim.text.isEmpty()) continue
        val selTf = pdfTypeface(prim.fontFamily, prim.isBold, prim.isItalic)
        tmpPaint.typeface = selTf
        tmpPaint.isFakeBoldText = prim.isBold && !selTf.isBold
        tmpPaint.textSkewX = if (prim.isItalic && !selTf.isItalic) -0.25f else 0f
        tmpPaint.textScaleX = prim.hScale.coerceIn(0.2f, 4f)
        tmpPaint.textSize = prim.size * scale
        val textStr = prim.text
        val measuredTotal = if (textStr.isNotEmpty()) tmpPaint.measureText(textStr) else prim.size * 0.5f
        val perGlyphMeasured = if (textStr.isNotEmpty()) measuredTotal / textStr.length else prim.size * 0.5f * scale
        var curPxPage = prim.origin.x
        for (c in textStr) {
            val px = curPxPage
            val singleGlyph = textStr.length == 1
            val cw = if (singleGlyph) prim.advance * scale else perGlyphMeasured
            val stepPage = if (singleGlyph) prim.advance else perGlyphMeasured / scale
            val left = px * scale
            val right = left + cw
            val baseline = ch - prim.origin.y * scale
            val top = baseline - prim.size * scale
            list.add(OrderedGlyph(-prim.origin.y, px, SelGlyph(c.toString(), left, top, right, baseline)))
            curPxPage += stepPage
        }
    }
    return list
}

/**
 * Run OCR on the page's largest raster image and synthesize selectable glyphs
 * from the recognized line boxes, so scanned PDFs with no embedded text layer
 * become selectable. Characters are distributed evenly across each line box
 * (monospace approximation) which is enough for word-level and range selection.
 * Returns empty if OCR is unavailable or the page has no decodable image.
 */
private suspend fun ocrPageGlyphs(page: SafePdfPage, ch: Float, scale: Float, ocr: OcrEngine): List<OrderedGlyph> {
    if (!ocr.isAvailable()) return emptyList()
    val img = page.primitives.filterIsInstance<PdfPrimitive.Image>()
        .mapNotNull { p -> p.bitmap?.let { p to it } }
        .maxByOrNull { (p, _) -> kotlin.math.abs(p.ctm[0] * p.ctm[3] - p.ctm[1] * p.ctm[2]) }
        ?: return emptyList()
    val (prim, bmp) = img
    val result = ocr.recognizeDetailed(bmp)
    if (result.boxes.isEmpty()) return emptyList()

    val a = prim.ctm[0]; val b = prim.ctm[1]; val c = prim.ctm[2]
    val d = prim.ctm[3]; val e = prim.ctm[4]; val f = prim.ctm[5]
    val bw = bmp.width.toFloat().coerceAtLeast(1f)
    val bh = bmp.height.toFloat().coerceAtLeast(1f)
    // Bitmap pixel (px,py) → page space via the image CTM on the unit square.
    // Image row 0 is the top, so v (unit-square, bottom-up) = 1 - py/bh.
    fun pageX(px: Float, py: Float): Float { val u = px / bw; val v = 1f - py / bh; return a * u + c * v + e }
    fun pageY(px: Float, py: Float): Float { val u = px / bw; val v = 1f - py / bh; return b * u + d * v + f }

    val out = ArrayList<OrderedGlyph>()
    for (box in result.boxes) {
        val text = box.text
        if (text.isEmpty()) continue
        val n = text.length
        val pxL = box.left.toFloat(); val pxR = box.right.toFloat()
        val pyT = box.top.toFloat(); val pyB = box.bottom.toFloat()
        val sxA = pageX(pxL, pyB) * scale; val sxB = pageX(pxR, pyB) * scale
        val yA = ch - pageY(pxL, pyT) * scale; val yB = ch - pageY(pxL, pyB) * scale
        val left = minOf(sxA, sxB); val right = maxOf(sxA, sxB)
        val top = minOf(yA, yB); val bottom = maxOf(yA, yB)
        if (right <= left || bottom <= top) continue
        val stepX = (right - left) / n
        val orderY = -pageY(pxL, pyB)
        for (k in 0 until n) {
            val gl = left + k * stepX
            out.add(
                OrderedGlyph(
                    orderY,
                    pageX(pxL + (pxR - pxL) * (k.toFloat() / n), pyB),
                    SelGlyph(text[k].toString(), gl, top, gl + stepX, bottom),
                )
            )
        }
    }
    return out
}

/** Nearest glyph index to [p], or null if none within [maxDist] page px. */
private fun nearestGlyph(g: List<SelGlyph>, p: Offset, maxDist: Float = Float.MAX_VALUE): Int? {
    var best = -1
    var bestD = Float.MAX_VALUE
    for (i in g.indices) {
        val gg = g[i]
        val cx = (gg.left + gg.right) / 2f
        val cy = (gg.top + gg.bottom) / 2f
        val dd = (cx - p.x) * (cx - p.x) + (cy - p.y) * (cy - p.y)
        if (dd < bestD) { bestD = dd; best = i }
    }
    return if (best >= 0 && bestD <= maxDist * maxDist) best else null
}

/** True when [a] and [b] belong to the same word (same line, no gap between them). */
private fun sameWord(a: SelGlyph, b: SelGlyph): Boolean {
    val h = maxOf(a.bottom - a.top, b.bottom - b.top, 1f)
    val vA = (a.top + a.bottom) / 2f
    val vB = (b.top + b.bottom) / 2f
    if (kotlin.math.abs(vA - vB) > 0.6f * h) return false // different line
    val gap = b.left - a.right
    return gap <= 0.4f * h
}

/** Expand the glyph index [i] to the whole word it belongs to (reading order). */
private fun wordRangeAt(g: List<SelGlyph>, i: Int): IntRange {
    if (i !in g.indices) return i..i
    if (g[i].ch.isBlank()) return i..i
    var lo = i
    var hi = i
    while (lo - 1 in g.indices && g[lo - 1].ch.isNotBlank() && sameWord(g[lo - 1], g[lo])) lo--
    while (hi + 1 in g.indices && g[hi + 1].ch.isNotBlank() && sameWord(g[hi], g[hi + 1])) hi++
    return lo..hi
}

/** Which selection handle (0 = start, 1 = end) is within grab range of [p], else null. */
private fun handleAt(g: List<SelGlyph>, p: Offset, r: IntRange): Int? {
    if (r.first !in g.indices || r.last !in g.indices) return null
    val s = g[r.first]
    val e = g[r.last]
    val dStart = kotlin.math.hypot(s.left - p.x, s.bottom - p.y)
    val dEnd = kotlin.math.hypot(e.right - p.x, e.bottom - p.y)
    val grab = 48f
    return when {
        dStart <= grab && dStart <= dEnd -> 0
        dEnd <= grab -> 1
        else -> null
    }
}

/** The selected substring for range [r] over [g] (empty if out of bounds). */
private fun selectionText(g: List<SelGlyph>, r: IntRange): String {
    if (g.isEmpty() || r.first !in g.indices || r.last !in g.indices || r.last < r.first) return ""
    return g.subList(r.first, r.last + 1).joinToString("") { it.ch }
}

/**
 * Text selection over a page's embedded text plus (for scanned pages) OCR text:
 *
 *  - **Long-press** selects the word under the finger; continuing the drag in the
 *    same motion extends the selection glyph-by-glyph.
 *  - The two **endpoint handles** can be dragged directly (no long-press) to grow
 *    or shrink the selection.
 *  - Selecting no longer auto-copies; the standard Android selection context
 *    menu (Copy / Select all) is shown via [LocalTextToolbar] — the real OS
 *    floating [android.view.ActionMode]. A quick tap dismisses the selection.
 */
@Composable
private fun TextSelectionLayer(page: SafePdfPage, ch: Float, scale: Float, ocr: OcrEngine?) {
    val textClipLabel = stringResource(R.string.text)
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    val textToolbar = LocalTextToolbar.current

    val embedded = remember(page, ch, scale) { buildEmbeddedGlyphs(page, ch, scale) }
    val needsOcr = remember(embedded) {
        embedded.count { it.glyph.ch.isNotBlank() } < MIN_EMBEDDED_GLYPHS_FOR_TEXT
    }
    var ocrGlyphs by remember(page, ch, scale) { mutableStateOf<List<OrderedGlyph>>(emptyList()) }
    LaunchedEffect(page, ch, scale, needsOcr, ocr) {
        ocrGlyphs = emptyList()
        if (ocr != null && needsOcr) {
            ocrGlyphs = runCatching { ocrPageGlyphs(page, ch, scale, ocr) }.getOrDefault(emptyList())
        }
    }

    val glyphs = remember(embedded, ocrGlyphs) {
        (embedded + ocrGlyphs).sortedWith(compareBy({ it.orderY }, { it.orderX })).map { it.glyph }
    }
    if (glyphs.isEmpty()) return

    var range by remember(page) { mutableStateOf<IntRange?>(null) }
    // True while a handle/word drag is in progress → the OS menu is hidden until
    // the gesture settles (mirrors native selection behavior).
    var isAdjusting by remember(page) { mutableStateOf(false) }
    // This layer's position in the composition root, tracked so the menu's anchor
    // rect follows scroll/zoom; recomputed whenever the layout is re-positioned.
    var selCoords by remember(page) { mutableStateOf<LayoutCoordinates?>(null) }
    var rootAnchor by remember(page) { mutableStateOf(Offset.Zero) }
    // Whether *this* page currently owns the (window-global) toolbar, so pages
    // without a selection never hide another page's menu during scroll.
    var showing by remember(page) { mutableStateOf(false) }
    val latestGlyphs by rememberUpdatedState(glyphs)

    DisposableEffect(Unit) {
        onDispose { if (showing) textToolbar.hide() }
    }

    // Drive the real OS floating ActionMode: show it anchored to the selection's
    // bounding rect (in root coordinates) whenever there's a settled selection.
    LaunchedEffect(range, isAdjusting, rootAnchor, glyphs) {
        val r = range
        val coords = selCoords
        if (r == null || isAdjusting || coords == null || !coords.isAttached ||
            r.first !in glyphs.indices || r.last !in glyphs.indices) {
            if (showing) { textToolbar.hide(); showing = false }
            return@LaunchedEffect
        }
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in r) {
            val g = glyphs[i]
            if (g.left < left) left = g.left
            if (g.top < top) top = g.top
            if (g.right > right) right = g.right
            if (g.bottom > bottom) bottom = g.bottom
        }
        val tl = coords.localToRoot(Offset(left, top))
        val br = coords.localToRoot(Offset(right, bottom))
        val rootRect = Rect(minOf(tl.x, br.x), minOf(tl.y, br.y), maxOf(tl.x, br.x), maxOf(tl.y, br.y))
        textToolbar.showMenu(
            rect = rootRect,
            onCopyRequested = {
                val text = selectionText(glyphs, r)
                if (text.isNotBlank()) {
                    scope.launch {
                        clipboard.setClipEntry(
                            androidx.compose.ui.platform.ClipEntry(
                                android.content.ClipData.newPlainText(textClipLabel, text)
                            )
                        )
                    }
                }
                range = null
            },
            onSelectAllRequested = { range = 0..glyphs.lastIndex },
        )
        showing = true
    }

    Canvas(
        // Keyed on page only so zoom/scale changes don't cancel an in-progress
        // selection; latest glyphs are read via rememberUpdatedState.
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> selCoords = c; rootAnchor = c.localToRoot(Offset.Zero) }
            .pointerInput(page) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // (A) If a selection exists and the finger lands on a handle,
                    //     drag it immediately (no long-press needed).
                    val existing = range
                    if (existing != null) {
                        val which = handleAt(latestGlyphs, down.position, existing)
                        if (which != null) {
                            down.consume()
                            isAdjusting = true
                            val anchor = if (which == 0) existing.last else existing.first
                            drag(down.id) { change ->
                                nearestGlyph(latestGlyphs, change.position)?.let { i ->
                                    range = minOf(i, anchor)..maxOf(i, anchor)
                                }
                                change.consume()
                            }
                            isAdjusting = false
                            return@awaitEachGesture
                        }
                    }

                    // (B) Otherwise classify the gesture: a long-press starts a word
                    //     selection; a quick tap dismisses; movement is a scroll (we
                    //     don't consume, so the parent list/zoom handles it).
                    val slop = viewConfiguration.touchSlop
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var moved = 0f
                        var last = down.position
                        var res = "hold"
                        while (true) {
                            val ev = awaitPointerEvent()
                            // A second finger means a pinch/zoom: bail without consuming
                            // so the parent transformable handles it even when the first
                            // finger is resting on text.
                            if (ev.changes.count { it.pressed } > 1) { res = "multitouch"; break }
                            val cpc = ev.changes.firstOrNull { it.id == down.id }
                            if (cpc == null) { res = "cancel"; break }
                            if (!cpc.pressed) { res = "tap"; break }
                            moved += (cpc.position - last).getDistance()
                            last = cpc.position
                            if (moved > slop) { res = "scroll"; break }
                        }
                        res
                    }

                    when (outcome) {
                        null -> {
                            // Long-press: select the word, then allow drag-to-extend.
                            val i = nearestGlyph(latestGlyphs, down.position, SELECT_HIT_PX)
                            if (i != null) {
                                down.consume()
                                val w = wordRangeAt(latestGlyphs, i)
                                range = w
                                // Keep the whole first word selected; extend outward
                                // toward the finger in either direction.
                                val wLo = w.first
                                val wHi = w.last
                                isAdjusting = true
                                drag(down.id) { change ->
                                    nearestGlyph(latestGlyphs, change.position)?.let { j ->
                                        range = minOf(j, wLo)..maxOf(j, wHi)
                                    }
                                    change.consume()
                                }
                                isAdjusting = false
                            }
                        }
                        "tap" -> if (range != null) range = null
                        else -> { /* scroll / multitouch / cancel: don't consume, let the parent scroll or pinch-zoom */ }
                    }
                }
            },
    ) {
        val r = range ?: return@Canvas
        val g = latestGlyphs
        for (i in r) {
            if (i !in g.indices) continue
            val gg = g[i]
            drawRect(
                color = Color(0x553F51B5),
                topLeft = Offset(gg.left, gg.top),
                size = Size(gg.right - gg.left, gg.bottom - gg.top),
            )
        }
        if (r.first in g.indices && r.last in g.indices) {
            val s = g[r.first]
            val e = g[r.last]
            drawCircle(Color(0xFF3F51B5), radius = 16f, center = Offset(s.left, s.bottom))
            drawCircle(Color(0xFF3F51B5), radius = 16f, center = Offset(e.right, e.bottom))
        }
    }
}

@Composable
private fun EditOverlay(
    page: SafePdfPage,
    annotations: List<SafeAnnotation>,
    selected: Long?,
    tool: EditTool,
    shape: ShapeKind,
    markup: MarkupKind,
    color: Color,
    strokeWidth: Float,
    cw: Float,
    ch: Float,
    scale: Float,
    toPage: (Offset) -> Offset,
    document: SafePdfDocument,
    index: Int,
    scope: CoroutineScope,
    onSelect: (Long?) -> Unit,
    onEdited: () -> Unit,
    onCreated: (Long) -> Unit,
    onMoved: (Long, List<Float>, List<Float>) -> Unit,
    onStartText: (TextSession) -> Unit,
    onRequestImage: (Offset) -> Unit,
    onRequestNote: (Offset) -> Unit,
    onRequestCallout: (Offset, Offset) -> Unit,
    polyDraft: PolyDraft?,
    onAddPolyPoint: (Offset) -> Unit,
) {
    // In-progress drag shape in screen space.
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var inkPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Accumulated move delta for the selected annotation (screen space).
    var moveDelta by remember { mutableStateOf(Offset.Zero) }

    // Keep annotation data stable for the gesture coroutine via updated states, so
    // the gesture is NOT cancelled when selected or annotations identity changes
    // (previously pointerInput(tool, selected, annotations) caused annotation drag
    // to cancel immediately after selecting at touchSlop threshold).
    val latestAnnotations by rememberUpdatedState(annotations)
    val latestSelected by rememberUpdatedState(selected)
    val latestToPage by rememberUpdatedState(toPage)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnStartText by rememberUpdatedState(onStartText)
    val latestOnRequestImage by rememberUpdatedState(onRequestImage)
    val latestOnRequestNote by rememberUpdatedState(onRequestNote)
    val latestOnRequestCallout by rememberUpdatedState(onRequestCallout)
    val latestOnAddPolyPoint by rememberUpdatedState(onAddPolyPoint)
    val latestOnCreated by rememberUpdatedState(onCreated)
    val latestOnEdited by rememberUpdatedState(onEdited)
    val latestOnMoved by rememberUpdatedState(onMoved)
    val latestDocument by rememberUpdatedState(document)
    val latestScope by rememberUpdatedState(scope)
    val latestScale by rememberUpdatedState(scale)
    val latestShape by rememberUpdatedState(shape)
    val latestMarkup by rememberUpdatedState(markup)
    val latestColor by rememberUpdatedState(color)
    val latestStroke by rememberUpdatedState(strokeWidth)
    val currentTool by rememberUpdatedState(tool)

    fun annotAt(screen: Offset): SafeAnnotation? {
        val p = latestToPage(screen)
        return latestAnnotations.lastOrNull { p.x in it.x0..it.x1 && p.y in it.y0..it.y1 }
    }

    val gestures = Modifier.pointerInput(currentTool, index) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val hitAtDown = annotAt(start)
            val selectMove = currentTool == EditTool.SELECT && hitAtDown != null
            val blockScroll =
                currentTool == EditTool.HIGHLIGHT || currentTool == EditTool.MARKUP || currentTool == EditTool.SHAPE ||
                    currentTool == EditTool.LINE || currentTool == EditTool.CALLOUT || currentTool == EditTool.REDACT ||
                    currentTool == EditTool.DRAW || selectMove
            // SELECT defers consume until dragging to allow pinch-zoom over annotation
            if (blockScroll && currentTool != EditTool.SELECT) {
                down.consume()
            }
            dragStart = start
            dragCurrent = start
            if (currentTool == EditTool.DRAW) inkPoints = listOf(start)
            if (currentTool == EditTool.SELECT) moveDelta = Offset.Zero

            var dragging = false
            var lastPos = start
            var draggedId: Long? = hitAtDown?.id
            var draggedInitRect: List<Float>? = hitAtDown?.let { listOf(it.x0, it.y0, it.x1, it.y1) }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val pos = change.position
                if (!dragging && (pos - start).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    if (selectMove) {
                        latestOnSelect(draggedId)
                        change.consume()
                        down.consume()
                    }
                }
                if (dragging) {
                    // Don't consume Select drags on empty space, so they scroll.
                    val consume = currentTool != EditTool.SELECT || selectMove
                    if (consume) change.consume()
                    when (currentTool) {
                        EditTool.HIGHLIGHT, EditTool.MARKUP, EditTool.SHAPE, EditTool.LINE, EditTool.CALLOUT, EditTool.REDACT -> dragCurrent = pos
                        EditTool.DRAW -> { dragCurrent = pos; inkPoints = inkPoints + pos }
                        EditTool.SELECT -> if (selectMove) moveDelta += (pos - lastPos)
                        else -> {}
                    }
                }
                lastPos = pos
            }

            if (!dragging) {
                when (currentTool) {
                    EditTool.TEXT -> {
                        val p = latestToPage(start)
                        latestOnStartText(
                            TextSession(
                                page = index,
                                origin = p,
                                size = 14f,
                                color = latestColor.toArgb(),
                                annotId = null,
                                value = TextFieldValue("Text", TextRange(0, 4)),
                            )
                        )
                    }
                    EditTool.IMAGE -> latestOnRequestImage(latestToPage(start))
                    EditTool.NOTE -> latestOnRequestNote(latestToPage(start))
                    EditTool.POLYLINE, EditTool.BEZIER -> latestOnAddPolyPoint(latestToPage(start))
                    EditTool.SELECT -> {
                        val hit = annotAt(start)
                        val sel = latestSelected
                        if (hit != null && hit.id == sel && hit.subtype == 1) {
                            val sz = ((hit.y1 - hit.y0) / 1.3f).coerceIn(6f, 72f)
                            latestOnStartText(
                                TextSession(
                                    page = index,
                                    origin = Offset(hit.x0, hit.y1),
                                    size = sz,
                                    color = hit.color,
                                    annotId = hit.id,
                                    value = TextFieldValue(hit.contents, TextRange(0, hit.contents.length)),
                                )
                            )
                        } else {
                            latestOnSelect(hit?.id)
                        }
                    }
                    else -> {}
                }
            } else {
                val s = dragStart
                val e = dragCurrent
                val doc = latestDocument
                val scp = latestScope
                when (currentTool) {
                    EditTool.HIGHLIGHT -> if (s != null && e != null) {
                        val a = latestToPage(s); val b = latestToPage(e)
                        scp.launch {
                            val id = doc.addHighlight(index, a.x, a.y, b.x, b.y, latestColor.toArgb())
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.MARKUP -> if (s != null && e != null) {
                        val a = latestToPage(s); val b = latestToPage(e)
                        scp.launch {
                            val mk = latestMarkup
                            val id = if (mk == MarkupKind.HIGHLIGHT) {
                                doc.addHighlight(index, a.x, a.y, b.x, b.y, latestColor.toArgb())
                            } else {
                                val kind = when (mk) {
                                    MarkupKind.STRIKEOUT -> 1
                                    MarkupKind.SQUIGGLY -> 2
                                    else -> 0
                                }
                                doc.addTextMarkup(index, a.x, a.y, b.x, b.y, latestColor.toArgb(), kind)
                            }
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.CALLOUT -> if (s != null && e != null) {
                        latestOnRequestCallout(latestToPage(s), latestToPage(e))
                    }
                    EditTool.SHAPE -> if (s != null && e != null) {
                        val rect = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                        val shp = latestShape
                        val lineWidth = if (shp.isFill) 0f else latestStroke
                        when (shp.geom) {
                            ShapeGeom.RECT -> {
                                val a = latestToPage(Offset(rect.left, rect.top)); val b = latestToPage(Offset(rect.right, rect.bottom))
                                scp.launch {
                                    val id = doc.addRect(index, a.x, a.y, b.x, b.y, latestColor.toArgb(), lineWidth, shp.isFill)
                                    latestOnCreated(id); latestOnEdited()
                                }
                            }
                            ShapeGeom.OVAL -> {
                                val a = latestToPage(Offset(rect.left, rect.top)); val b = latestToPage(Offset(rect.right, rect.bottom))
                                scp.launch {
                                    val id = doc.addOval(index, a.x, a.y, b.x, b.y, latestColor.toArgb(), lineWidth, shp.isFill)
                                    latestOnCreated(id); latestOnEdited()
                                }
                            }
                            ShapeGeom.POLYGON -> {
                                val unit = shp.unitPolygon()
                                val flat = FloatArray(unit.size * 2)
                                unit.forEachIndexed { i, u ->
                                    val pp = latestToPage(mapUnit(u, rect)); flat[i * 2] = pp.x; flat[i * 2 + 1] = pp.y
                                }
                                scp.launch {
                                    val id = doc.addPoly(index, flat, latestColor.toArgb(), lineWidth, shp.isFill, closed = true)
                                    latestOnCreated(id); latestOnEdited()
                                }
                            }
                        }
                    }
                    EditTool.LINE -> if (s != null && e != null) {
                        val a = latestToPage(s); val b = latestToPage(e)
                        scp.launch {
                            val id = doc.addPoly(index, floatArrayOf(a.x, a.y, b.x, b.y), latestColor.toArgb(), latestStroke, fill = false, closed = false)
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.REDACT -> if (s != null && e != null) {
                        val a = latestToPage(Offset(minOf(s.x, e.x), minOf(s.y, e.y)))
                        val b = latestToPage(Offset(maxOf(s.x, e.x), maxOf(s.y, e.y)))
                        scp.launch {
                            val id = doc.addRedaction(index, a.x, a.y, b.x, b.y)
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.DRAW -> {
                        val pts = inkPoints
                        if (pts.size >= 2) {
                            val flat = FloatArray(pts.size * 2)
                            pts.forEachIndexed { i, o ->
                                val pp = latestToPage(o); flat[i * 2] = pp.x; flat[i * 2 + 1] = pp.y
                            }
                            scp.launch {
                                val id = doc.addInk(index, latestColor.toArgb(), latestStroke, flat)
                                latestOnCreated(id); latestOnEdited()
                            }
                        }
                    }
                    EditTool.SELECT -> if (selectMove) {
                        val id = draggedId
                        if (id != null) {
                            val dx = moveDelta.x / latestScale
                            val dy = -moveDelta.y / latestScale
                            if (dx != 0f || dy != 0f) {
                                val initR = draggedInitRect
                                if (initR != null) {
                                    val newR = listOf(initR[0] + dx, initR[1] + dy, initR[2] + dx, initR[3] + dy)
                                    scp.launch {
                                        doc.moveAnnotation(index, id, newR[0], newR[1], newR[2], newR[3])
                                        latestOnMoved(id, initR, newR)
                                        latestOnEdited()
                                    }
                                } else {
                                    val a = latestAnnotations.firstOrNull { it.id == id }
                                    if (a != null) {
                                        val oldR = listOf(a.x0, a.y0, a.x1, a.y1)
                                        val newR = listOf(a.x0 + dx, a.y0 + dy, a.x1 + dx, a.y1 + dy)
                                        scp.launch {
                                            doc.moveAnnotation(index, id, newR[0], newR[1], newR[2], newR[3])
                                            latestOnMoved(id, oldR, newR)
                                            latestOnEdited()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            dragStart = null
            dragCurrent = null
            if (currentTool == EditTool.DRAW) inkPoints = emptyList()
            moveDelta = Offset.Zero
        }
    }

    Canvas(Modifier.fillMaxSize().then(gestures)) {
        // Selection highlight.
        val sel = latestAnnotations.firstOrNull { it.id == latestSelected }
        if (sel != null) {
            val left = sel.x0 * scale + moveDelta.x
            val top = ch - sel.y1 * scale + moveDelta.y
            drawRect(
                color = Color(0xFF2196F3),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size((sel.x1 - sel.x0) * scale, (sel.y1 - sel.y0) * scale),
                style = Stroke(width = 3f),
            )
        }
        // In-progress shapes.
        val s = dragStart
        val e = dragCurrent
        if (s != null && e != null && tool == EditTool.HIGHLIGHT) {
            drawRect(
                color = color.copy(alpha = 0.35f),
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = Fill,
            )
        }
        if (s != null && e != null && tool == EditTool.MARKUP) {
            val left = minOf(s.x, e.x); val right = maxOf(s.x, e.x)
            val top = minOf(s.y, e.y); val bottom = maxOf(s.y, e.y)
            when (markup) {
                MarkupKind.HIGHLIGHT -> drawRect(
                    color = color.copy(alpha = 0.35f),
                    topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Fill,
                )
                MarkupKind.STRIKEOUT -> drawLine(color, Offset(left, (top + bottom) / 2f), Offset(right, (top + bottom) / 2f), strokeWidth = 2f)
                else -> drawLine(color, Offset(left, bottom - 2f), Offset(right, bottom - 2f), strokeWidth = 2f)
            }
        }
        if (s != null && e != null && tool == EditTool.CALLOUT) {
            drawLine(color, s, e, strokeWidth = 2f)
            drawRect(color = color, topLeft = e, size = Size(120f, 40f), style = Stroke(width = 2f))
        }
        if (s != null && e != null && tool == EditTool.REDACT) {
            drawRect(
                color = Color.Black,
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = Fill,
            )
        }
        if (s != null && e != null && tool == EditTool.SHAPE) {
            val rect = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
            val topLeft = Offset(rect.left, rect.top)
            val sz = Size(rect.width, rect.height)
            val style = if (shape.isFill) Fill else Stroke(width = 2f)
            when (shape.geom) {
                ShapeGeom.RECT -> drawRect(color = color, topLeft = topLeft, size = sz, style = style)
                ShapeGeom.OVAL -> drawOval(color = color, topLeft = topLeft, size = sz, style = style)
                ShapeGeom.POLYGON -> {
                    val pts = shape.unitPolygon().map { mapUnit(it, rect) }
                    if (pts.size >= 2) {
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(path, color, style = style)
                    }
                }
            }
        }
        if (s != null && e != null && tool == EditTool.LINE) {
            drawLine(color, s, e, strokeWidth = 2f)
        }
        // In-progress polyline / Bézier: draw placed points and connecting path.
        if (polyDraft != null && polyDraft.points.isNotEmpty()) {
            val screenPts = polyDraft.points.map { Offset(it.x * scale, ch - it.y * scale) }
            val path = Path().apply {
                moveTo(screenPts[0].x, screenPts[0].y)
                screenPts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2f))
            for (p in screenPts) {
                drawCircle(color = color, radius = 5f, center = p)
            }
        }
        if (tool == EditTool.DRAW && inkPoints.size >= 2) {
            val path = Path().apply {
                moveTo(inkPoints[0].x, inkPoints[0].y)
                inkPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun FormFieldOverlay(
    fields: List<SafeFormField>,
    ch: Float,
    scale: Float,
    document: SafePdfDocument,
    index: Int,
    scope: CoroutineScope,
    onEdited: () -> Unit,
) {
    val density = LocalDensity.current
    for (field in fields) {
        val leftDp = with(density) { (field.x0 * scale).toDp() }
        val topDp = with(density) { (ch - field.y1 * scale).toDp() }
        val wDp = with(density) { ((field.x1 - field.x0) * scale).toDp() }
        val hDp = with(density) { ((field.y1 - field.y0) * scale).toDp() }

        when (field.type) {
            0 -> { // text field
                var text by remember(field.id, field.value) { mutableStateOf(field.value) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x332196F3)),
                    singleLine = true,
                )
                DisposableEffect(field.id) {
                    onDispose {
                        if (text != field.value) {
                            scope.launch { document.setChoiceField(index, field.id, text); onEdited() }
                        }
                    }
                }
            }
            1 -> { // checkbox / button
                var checked by remember(field.id, field.checked) { mutableStateOf(field.checked) }
                Box(Modifier.padding(start = leftDp, top = topDp).size(wDp, hDp)) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            scope.launch { document.setCheckbox(index, field.id, it); onEdited() }
                        },
                        // The page behind this is a fixed white sheet, so the
                        // control cannot follow the theme's on-surface roles.
                        colors = CheckboxDefaults.colors(
                            uncheckedColor = Color(0xFF49454F),
                            checkedColor = Color(0xFF1F6FC0),
                            checkmarkColor = Color.White,
                        ),
                    )
                }
            }
            2 -> { // choice / dropdown (editable combo): edit the value inline
                var text by remember(field.id, field.value) { mutableStateOf(field.value) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x3300B0FF)),
                    singleLine = true,
                )
                DisposableEffect(field.id) {
                    onDispose {
                        if (text != field.value) {
                            scope.launch { document.setTextField(index, field.id, text); onEdited() }
                        }
                    }
                }
            }
            3 -> { // signature / other: show a tappable placeholder
                Box(
                    Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x22000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.sign),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF49454F),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditToolbar(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
    shape: ShapeKind,
    onShape: (ShapeKind) -> Unit,
    markup: MarkupKind,
    onMarkup: (MarkupKind) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    onStyle: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    BottomAppBar {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton({ IconSelect(tint = it) }, tool == EditTool.SELECT) { onTool(EditTool.SELECT) }
            ToolButton({ IconTextTool(tint = it) }, tool == EditTool.TEXT) { onTool(EditTool.TEXT) }
            MarkupMenuButton(markup = markup, active = tool == EditTool.MARKUP, onMarkup = onMarkup)
            ToolButton({ IconDraw(tint = it) }, tool == EditTool.DRAW) { onTool(EditTool.DRAW) }
            ShapeMenuButton(shape = shape, active = tool == EditTool.SHAPE, onShape = onShape)
            LinesMenuButton(tool = tool, onTool = onTool)
            ToolButton({ IconNote(tint = it) }, tool == EditTool.NOTE) { onTool(EditTool.NOTE) }
            ToolButton({ IconCallout(tint = it) }, tool == EditTool.CALLOUT) { onTool(EditTool.CALLOUT) }
            ToolButton({ IconRedact(tint = it) }, tool == EditTool.REDACT) { onTool(EditTool.REDACT) }
            ToolButton({ IconImage(tint = it) }, tool == EditTool.IMAGE) { onTool(EditTool.IMAGE) }

            for (c in listOf(Color.Red, Color.Yellow, Color.Blue, Color.Black)) {
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c)
                        .pointerInput(c) { detectTapGestures { onColor(c) } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (c.copy(alpha = 1f) == color.copy(alpha = 1f)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }

            IconButton(onStyle) {
                IconStyle()
            }
            if (canDelete) {
                IconButton(onDuplicate) {
                    IconCopy()
                }
                IconButton(onDelete) { IconDelete() }
            }
        }
    }
}

/** Dropdown for text-markup tools (highlight, underline, strikeout, squiggly). */
@Composable
private fun MarkupMenuButton(
    markup: MarkupKind,
    active: Boolean,
    onMarkup: (MarkupKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            markup.icon()(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in MarkupKind.entries) {
                DropdownMenuItem(
                    leadingIcon = {
                        kind.icon()(
                            if (active && kind == markup) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(kind.label()) },
                    onClick = { expanded = false; onMarkup(kind) },
                )
            }
        }
    }
}

/** A single toolbar button whose icon reflects the selected [shape]; tapping it
 * opens a dropdown to pick a rectangle/ellipse (outline or filled). */
@Composable
private fun ShapeMenuButton(
    shape: ShapeKind,
    active: Boolean,
    onShape: (ShapeKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            shape.icon()(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in ShapeKind.entries) {
                DropdownMenuItem(
                    leadingIcon = {
                        kind.icon()(
                            if (active && kind == shape) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(kind.label()) },
                    onClick = { expanded = false; onShape(kind) },
                )
            }
        }
    }
}

/** Dropdown for the line tools (straight line, polyline, Bézier). The button
 * icon reflects the active line tool. */
@Composable
private fun LinesMenuButton(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
) {
    val lineTools = listOf(
        Triple<EditTool, @Composable (Color) -> Unit, String>(EditTool.LINE, { t -> IconLine(tint = t) }, "Line"),
        Triple<EditTool, @Composable (Color) -> Unit, String>(EditTool.POLYLINE, { t -> IconPolyline(tint = t) }, "Polyline"),
        Triple<EditTool, @Composable (Color) -> Unit, String>(EditTool.BEZIER, { t -> IconBezier(tint = t) }, "Bézier curve"),
    )
    val active = tool == EditTool.LINE || tool == EditTool.POLYLINE || tool == EditTool.BEZIER
    val currentIcon: @Composable (Color) -> Unit = lineTools.firstOrNull { it.first == tool }?.second ?: { t -> IconLine(tint = t) }
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            currentIcon(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((t, icon, label) in lineTools) {
                DropdownMenuItem(
                    leadingIcon = {
                        icon(
                            if (tool == t) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(label) },
                    onClick = { expanded = false; onTool(t) },
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: @Composable (tint: Color) -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick) {
        icon(
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Style picker: color palette + custom RGB, opacity, and line-width sliders.
 * Affects newly drawn annotations. */
@Composable
private fun StyleDialog(
    color: Color,
    onColor: (Color) -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    strokeWidth: Float,
    onWidth: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = listOf(
        Color.Red, Color(0xFFFF9800), Color.Yellow, Color(0xFF4CAF50),
        Color.Cyan, Color.Blue, Color(0xFF3F51B5), Color(0xFF9C27B0),
        Color.Magenta, Color.Black, Color.Gray, Color.White,
    )
    var r by remember(color) { mutableFloatStateOf(color.red) }
    var g by remember(color) { mutableFloatStateOf(color.green) }
    var b by remember(color) { mutableFloatStateOf(color.blue) }
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.done)) } },
        title = { Text(stringResource(R.string.style)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    for (c in palette) {
                        Box(
                            Modifier
                                .padding(3.dp).size(28.dp).clip(CircleShape).background(c)
                                .pointerInput(c) {
                                    detectTapGestures {
                                        r = c.red; g = c.green; b = c.blue; onColor(Color(r, g, b))
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c.copy(alpha = 1f) == color.copy(alpha = 1f)) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFCCCCCC)))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.red), style = MaterialTheme.typography.labelSmall)
                Slider(value = r, onValueChange = { r = it; onColor(Color(r, g, b)) })
                Text(stringResource(R.string.green), style = MaterialTheme.typography.labelSmall)
                Slider(value = g, onValueChange = { g = it; onColor(Color(r, g, b)) })
                Text(stringResource(R.string.blue), style = MaterialTheme.typography.labelSmall)
                Slider(value = b, onValueChange = { b = it; onColor(Color(r, g, b)) })
                Text(stringResource(R.string.opacity, (opacity * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                Slider(value = opacity, onValueChange = onOpacity, valueRange = 0.1f..1f)
                Text(stringResource(R.string.line_width, strokeWidth.toInt()), style = MaterialTheme.typography.labelSmall)
                Slider(value = strokeWidth, onValueChange = onWidth, valueRange = 1f..20f)
            }
        },
    )
}

/** Map the renderer's [BlendMode] to a Compose blend mode (SrcOver = normal). */
private fun BlendMode.toCompose(): androidx.compose.ui.graphics.BlendMode = when (this) {
    BlendMode.Normal -> androidx.compose.ui.graphics.BlendMode.SrcOver
    BlendMode.Multiply -> androidx.compose.ui.graphics.BlendMode.Multiply
    BlendMode.Screen -> androidx.compose.ui.graphics.BlendMode.Screen
    BlendMode.Overlay -> androidx.compose.ui.graphics.BlendMode.Overlay
    BlendMode.Darken -> androidx.compose.ui.graphics.BlendMode.Darken
    BlendMode.Lighten -> androidx.compose.ui.graphics.BlendMode.Lighten
    BlendMode.ColorDodge -> androidx.compose.ui.graphics.BlendMode.ColorDodge
    BlendMode.ColorBurn -> androidx.compose.ui.graphics.BlendMode.ColorBurn
    BlendMode.HardLight -> androidx.compose.ui.graphics.BlendMode.Hardlight
    BlendMode.SoftLight -> androidx.compose.ui.graphics.BlendMode.Softlight
    BlendMode.Difference -> androidx.compose.ui.graphics.BlendMode.Difference
    BlendMode.Exclusion -> androidx.compose.ui.graphics.BlendMode.Exclusion
    BlendMode.Hue -> androidx.compose.ui.graphics.BlendMode.Hue
    BlendMode.Saturation -> androidx.compose.ui.graphics.BlendMode.Saturation
    BlendMode.Color -> androidx.compose.ui.graphics.BlendMode.Color
    BlendMode.Luminosity -> androidx.compose.ui.graphics.BlendMode.Luminosity
}

/** The [android.graphics.BlendMode] for a renderer [BlendMode]. */
private fun BlendMode.toAndroid(): android.graphics.BlendMode = when (this) {
    BlendMode.Normal -> android.graphics.BlendMode.SRC_OVER
    BlendMode.Multiply -> android.graphics.BlendMode.MULTIPLY
    BlendMode.Screen -> android.graphics.BlendMode.SCREEN
    BlendMode.Overlay -> android.graphics.BlendMode.OVERLAY
    BlendMode.Darken -> android.graphics.BlendMode.DARKEN
    BlendMode.Lighten -> android.graphics.BlendMode.LIGHTEN
    BlendMode.ColorDodge -> android.graphics.BlendMode.COLOR_DODGE
    BlendMode.ColorBurn -> android.graphics.BlendMode.COLOR_BURN
    BlendMode.HardLight -> android.graphics.BlendMode.HARD_LIGHT
    BlendMode.SoftLight -> android.graphics.BlendMode.SOFT_LIGHT
    BlendMode.Difference -> android.graphics.BlendMode.DIFFERENCE
    BlendMode.Exclusion -> android.graphics.BlendMode.EXCLUSION
    BlendMode.Hue -> android.graphics.BlendMode.HUE
    BlendMode.Saturation -> android.graphics.BlendMode.SATURATION
    BlendMode.Color -> android.graphics.BlendMode.COLOR
    BlendMode.Luminosity -> android.graphics.BlendMode.LUMINOSITY
}

/** Set (or clear) the blend mode on a reused native [android.graphics.Paint]. */
private fun android.graphics.Paint.setBlend(blend: BlendMode) {
    blendMode = if (blend == BlendMode.Normal) null else blend.toAndroid()
}

/**
 * Draw a page's primitives, mapping PDF page space (origin bottom-left) to the
 * canvas (origin top-left) with a uniform fit-to-width scale + Y-flip.
 */
internal fun DrawScope.drawSafePage(page: SafePdfPage) {
    val scale = size.width / page.width
    val h = size.height

    fun map(p: Offset) = Offset(p.x * scale, h - p.y * scale)

    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isDither = true
        isSubpixelText = true
        isLinearText = true
    }
    val textStrokePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isDither = true
        isSubpixelText = true
        isLinearText = true
        style = android.graphics.Paint.Style.STROKE
    }
    val imagePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    val nativeCanvas = drawContext.canvas.nativeCanvas
    var saveCount = 0
    val clipPath = android.graphics.Path()
    var groupSaveCount = 0
    // Active ExtGState soft-mask brackets. Each frame tracks how many mask
    // layers were opened at the SoftMaskContent marker so SoftMaskPop can undo
    // exactly the content layer + mask (+ luma) layers.
    class SoftMaskFrame(val maskType: Int) { var maskLayers = 0 }
    val softMaskStack = ArrayDeque<SoftMaskFrame>()

    fun clipOffsetList(pts: List<Offset>): List<Offset> = pts.map { map(it) }

    // Bezier-retentive clip path (v4) and text-clip accumulation (Tr 4-7).
    fun pathOpsToPath(ops: List<PathOp>): android.graphics.Path {
        val p = android.graphics.Path()
        for (op in ops) {
            when (op) {
                is PathOp.Move -> { val o = map(Offset(op.x, op.y)); p.moveTo(o.x, o.y) }
                is PathOp.Line -> { val o = map(Offset(op.x, op.y)); p.lineTo(o.x, o.y) }
                is PathOp.Cubic -> {
                    val a = map(Offset(op.x1, op.y1))
                    val b = map(Offset(op.x2, op.y2))
                    val c = map(Offset(op.x3, op.y3))
                    p.cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
                }
                PathOp.Close -> p.close()
            }
        }
        return p
    }
    val textClipPath = android.graphics.Path()
    var hasTextClip = false
    val glyphPathPaint = android.graphics.Paint().apply { isAntiAlias = true }
    val tmpGlyphPath = android.graphics.Path()

    for (prim in page.primitives) {
        // Primitive count guard double-check (OOM avoidance if Rust guard fails)
        if (saveCount > 64 || groupSaveCount > 32) {
            android.util.Log.w("SafePdfViewer", "Clip/group depth guard, skipping remaining prims")
            break
        }
        when (prim) {
            is PdfPrimitive.FillPath -> {
                val path = Path()
                var any = false
                for (contour in prim.contours) {
                    if (contour.size < 2) continue
                    val first = map(contour[0])
                    path.moveTo(first.x, first.y)
                    for (i in 1 until contour.size) {
                        val p = map(contour[i])
                        path.lineTo(p.x, p.y)
                    }
                    path.close()
                    any = true
                }
                if (any) {
                    // All contours in one path so interior contours (holes/glyph
                    // counters) are cut out by the winding rule.
                    path.fillType = if (prim.evenOdd) {
                        androidx.compose.ui.graphics.PathFillType.EvenOdd
                    } else {
                        androidx.compose.ui.graphics.PathFillType.NonZero
                    }
                    drawPath(path, Color(prim.color), style = Fill, blendMode = prim.blend.toCompose())
                }
            }

            is PdfPrimitive.StrokePath -> {
                val path = prim.points.toPath(::map) ?: continue
                // Fix double-scale bug: Rust already scales dash by CTM avg; Kotlin should NOT re-scale by *scale again for width? Per plan 2104-2105 double-scale.
                // We keep dash scaling by *scale for canvas space but avoid double scaling width which Rust already did.
                // Actually width already device-scaled in Rust via CTM avg, so we should NOT multiply by scale again beyond min clamp?
                // To preserve visual, we use prim.width directly coerceAtLeast(1f) but also consider canvas scale? We'll use prim.width * scale for stroke width only if width < threshold, else prim.width.
                // Correct logic: width from Rust is already CTM-scaled to page space * device? For fidelity we map as width * scale (page->canvas) as before, but dash was also scaled - need to ensure dash not double-scaled.
                // Rust: dash = gs.dash * CTM_avg_scale (device). Kotlin: dash * scale maps page->canvas. That's actually double scaling? Plan says dash scaling double-scale bug 2104-2105.
                // Fix: dash already scaled in Rust, so we should use dash directly, not *scale. Similarly width already scaled? But width scaling via CTM is needed for page->canvas.
                // We'll implement: width = prim.width * scale, dash = prim.dash (not multiplied) + phase = prim.dashPhase (not *scale) unless phase small.
                val dash = prim.dash
                val pathEffect = if (dash.size >= 2) {
                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        dash,
                        prim.dashPhase,
                    )
                } else {
                    null
                }
                val cap = when (prim.cap) {
                    1 -> androidx.compose.ui.graphics.StrokeCap.Round
                    2 -> androidx.compose.ui.graphics.StrokeCap.Square
                    else -> androidx.compose.ui.graphics.StrokeCap.Butt
                }
                val join = when (prim.join) {
                    1 -> androidx.compose.ui.graphics.StrokeJoin.Round
                    2 -> androidx.compose.ui.graphics.StrokeJoin.Bevel
                    else -> androidx.compose.ui.graphics.StrokeJoin.Miter
                }
                // Miter limit is in line-width units per spec, not device pixels — do NOT scale by canvas scale (was a bug at 2104-2105).
                drawPath(
                    path,
                    Color(prim.color),
                    style = Stroke(
                        width = (prim.width * scale).coerceAtLeast(1f),
                        miter = prim.miter,
                        cap = cap,
                        join = join,
                        pathEffect = pathEffect,
                    ),
                    blendMode = prim.blend.toCompose(),
                )
            }

            is PdfPrimitive.Text -> {
                // Embedded-font glyphs are painted via their real outline as Fill
                // prims; this Text is kept only for selection/search — never painted.
                if (prim.outline) continue
                if (prim.text.isBlank()) continue
                val origin = map(prim.origin)
                val ts = (prim.size * scale).coerceAtLeast(1f)
                val rm = prim.renderMode
                val isStrokeOnly = prim.strokeColor != null && prim.color == prim.strokeColor

                // v8: substitute the embedded font with a system typeface matching the
                // generic family (sans/serif/mono) + bold/italic recovered by Rust. Rust
                // already emits one glyph per prim at its exact advance-based origin, so
                // letters are correctly spaced without distorting glyph widths — we draw
                // each glyph at its natural width and only apply Tz (hScale).
                val tf = pdfTypeface(prim.fontFamily, prim.isBold, prim.isItalic)
                // Only synthesize bold/italic when the real typeface can't supply it,
                // so a genuine bold serif isn't double-weighted into a heavy/wrong look.
                val fakeBold = prim.isBold && !tf.isBold
                val skew = if (prim.isItalic && !tf.isItalic) -0.25f else 0f
                val hs = prim.hScale.coerceIn(0.2f, 4f)
                textPaint.typeface = tf
                textPaint.textSize = ts
                textPaint.isFakeBoldText = fakeBold
                textPaint.textSkewX = skew
                textPaint.textScaleX = hs
                textStrokePaint.typeface = tf
                textStrokePaint.isFakeBoldText = fakeBold
                textStrokePaint.textSkewX = skew
                textStrokePaint.textScaleX = hs
                glyphPathPaint.typeface = tf
                glyphPathPaint.textSize = ts
                glyphPathPaint.textSkewX = skew
                glyphPathPaint.textScaleX = hs
                glyphPathPaint.isFakeBoldText = fakeBold

                // Paint the glyphs unless this is a clip-only run (Tr 7).
                if (rm != 7) {
                    if (prim.strokeColor != null && prim.strokeWidth > 0f) {
                        textStrokePaint.color = prim.strokeColor
                        textStrokePaint.textSize = ts
                        textStrokePaint.strokeWidth = (prim.strokeWidth * scale).coerceAtLeast(0.5f)
                        textStrokePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        textStrokePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        textStrokePaint.setBlend(prim.blend)
                        nativeCanvas.drawText(prim.text, origin.x, origin.y, textStrokePaint)
                    }
                    if (!isStrokeOnly) {
                        textPaint.color = prim.color
                        textPaint.textSize = ts
                        textPaint.setBlend(prim.blend)
                        nativeCanvas.drawText(prim.text, origin.x, origin.y, textPaint)
                    }
                }

                // Accumulate glyph outlines for text-clip render modes (Tr 4-7),
                // applied at the following TextClipApply marker. Use styled glyphPathPaint
                // so clip matches bold/italic visual.
                if (rm in 4..7) {
                    tmpGlyphPath.reset()
                    glyphPathPaint.getTextPath(prim.text, 0, prim.text.length, origin.x, origin.y, tmpGlyphPath)
                    textClipPath.addPath(tmpGlyphPath)
                    hasTextClip = true
                }
            }

            is PdfPrimitive.Image -> {
                val bmp = prim.bitmap ?: continue
                // Placeholder for JBIG2 failure: instead of continue show gray box with warning per Phase 6
                // decodeBitmap now returns placeholder gray bitmap when unknown format
                val matrixAlpha = prim.alpha.coerceIn(0f,1f)
                if (matrixAlpha < 1f) {
                    imagePaint.alpha = (matrixAlpha*255).toInt()
                } else {
                    imagePaint.alpha = 255
                }
                val m = prim.ctm
                fun unitToCanvas(u: Float, v: Float): Offset {
                    val pageX = m[0] * u + m[2] * v + m[4]
                    val pageY = m[1] * u + m[3] * v + m[5]
                    return map(Offset(pageX, pageY))
                }
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                val src = floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh)
                val c00 = unitToCanvas(0f, 1f)
                val c10 = unitToCanvas(1f, 1f)
                val c11 = unitToCanvas(1f, 0f)
                val c01 = unitToCanvas(0f, 0f)
                val dst = floatArrayOf(c00.x, c00.y, c10.x, c10.y, c11.x, c11.y, c01.x, c01.y)
                val mat = android.graphics.Matrix()
                mat.setPolyToPoly(src, 0, dst, 0, 4)
                imagePaint.setBlend(prim.blend)
                nativeCanvas.drawBitmap(bmp, mat, imagePaint)
                imagePaint.setBlend(BlendMode.Normal)
                imagePaint.alpha = 255
            }

            is PdfPrimitive.ClipPush -> {
                // Save and apply clip; prefer the bezier-retentive path (v4) for
                // accurate curved clips, falling back to the flattened polyline.
                nativeCanvas.save()
                saveCount++
                val ops = prim.pathOps
                if (ops != null && ops.isNotEmpty()) {
                    val cp = pathOpsToPath(ops)
                    cp.fillType = if (prim.evenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                    nativeCanvas.clipPath(cp)
                } else if (prim.points.size >= 3) {
                    clipPath.reset()
                    val mapped = clipOffsetList(prim.points)
                    clipPath.moveTo(mapped[0].x, mapped[0].y)
                    for (i in 1 until mapped.size) {
                        clipPath.lineTo(mapped[i].x, mapped[i].y)
                    }
                    clipPath.close()
                    clipPath.fillType = if (prim.evenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                    nativeCanvas.clipPath(clipPath)
                }
            }

            is PdfPrimitive.ClipPop -> {
                if (saveCount > 0) {
                    nativeCanvas.restore()
                    saveCount--
                }
            }

            is PdfPrimitive.TextClipApply -> {
                // Intersect the accumulated glyph outlines into the clip. Paired
                // with a later ClipPop (Rust incremented the clip depth).
                nativeCanvas.save()
                saveCount++
                if (hasTextClip) {
                    nativeCanvas.clipPath(textClipPath)
                }
                textClipPath.reset()
                hasTextClip = false
            }

            is PdfPrimitive.GroupPush -> {
                // Transparency group with alpha + blend. Isolated groups are the
                // default with saveLayer (a fresh backdrop-free layer); knockout
                // groups are not directly expressible with Canvas layers and are
                // approximated as non-knockout.
                val alpha = prim.alpha.coerceIn(0f,1f)
                val blend = prim.blend
                try {
                    val paint = android.graphics.Paint()
                    paint.alpha = (alpha*255).toInt()
                    if (blend != BlendMode.Normal) {
                        paint.blendMode = blend.toAndroid()
                    }
                    nativeCanvas.saveLayer(null, paint)
                    groupSaveCount++
                    // Also track in saveCount for balanced restore? Keep separate.
                } catch (t: Throwable) {
                    android.util.Log.w("SafePdfViewer", "GroupPush saveLayer failed", t)
                    nativeCanvas.save()
                    saveCount++
                }
            }

            is PdfPrimitive.GroupPop -> {
                if (groupSaveCount > 0) {
                    nativeCanvas.restore()
                    groupSaveCount--
                } else if (saveCount >0) {
                    // fallback balanced if group tracking drifted
                    nativeCanvas.restore()
                    saveCount--
                }
            }

            is PdfPrimitive.SoftMaskPush -> {
                // Open the layer that will hold the masked content.
                nativeCanvas.saveLayer(null, null)
                softMaskStack.addLast(SoftMaskFrame(prim.maskType))
            }

            is PdfPrimitive.SoftMaskContent -> {
                val frame = softMaskStack.lastOrNull()
                if (frame != null) {
                    // The mask layer composites onto the content layer with
                    // DST_IN, so the content is kept only where the mask has alpha.
                    val maskPaint = android.graphics.Paint()
                    maskPaint.blendMode = android.graphics.BlendMode.DST_IN
                    nativeCanvas.saveLayer(null, maskPaint)
                    frame.maskLayers = 1
                    // Luminosity masks: convert the mask's luminance to alpha
                    // (RGB -> 0, A = Rec.709 luma) as the luma layer composites down.
                    if (frame.maskType == 1) {
                        val lumaPaint = android.graphics.Paint()
                        val lm = android.graphics.ColorMatrix(
                            floatArrayOf(
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0f, 0f,
                                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                            )
                        )
                        lumaPaint.colorFilter = android.graphics.ColorMatrixColorFilter(lm)
                        nativeCanvas.saveLayer(null, lumaPaint)
                        frame.maskLayers = 2
                    }
                }
            }

            is PdfPrimitive.SoftMaskPop -> {
                val frame = softMaskStack.removeLastOrNull()
                if (frame != null) {
                    // Restore the mask (+luma) layers, then the content layer.
                    repeat(frame.maskLayers) { nativeCanvas.restore() }
                    nativeCanvas.restore()
                }
            }
        }
    }
    // Ensure balanced restore
    while (softMaskStack.isNotEmpty()) {
        val frame = softMaskStack.removeLast()
        repeat(frame.maskLayers) { nativeCanvas.restore() }
        nativeCanvas.restore()
    }
    while (groupSaveCount > 0) {
        nativeCanvas.restore()
        groupSaveCount--
    }
    while (saveCount > 0) {
        nativeCanvas.restore()
        saveCount--
    }
}

private inline fun List<Offset>.toPath(map: (Offset) -> Offset): Path? {
    if (size < 2) return null
    val path = Path()
    val first = map(this[0])
    path.moveTo(first.x, first.y)
    for (i in 1 until size) {
        val p = map(this[i])
        path.lineTo(p.x, p.y)
    }
    return path
}

private class JpegImage(val bytes: ByteArray, val width: Int, val height: Int)

/** Read [uri] and re-encode it as JPEG for a stamp; null on failure. */
private fun readAsJpeg(context: android.content.Context, uri: Uri): JpegImage? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    JpegImage(out.toByteArray(), bmp.width, bmp.height)
}.getOrNull()
