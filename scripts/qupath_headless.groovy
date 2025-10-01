// ============================================================================
// Lipid 40× Headless pipeline (all outputs under outDir)
// Run with:
//   qupath script --image /path/to/wsi.ome.tif \
//                 --script /worker/scripts/qupath_headless.groovy \
//                 --args "out=/tmp/out;classifier=/worker/models/lipid_1.json;export_geojson=true;export_mask=true;ds=16"
// ============================================================================

// ---- Parse args -------------------------------------------------------------
def argsMap = getQuPath().getScriptParameters()
String outDir     = argsMap.getOrDefault('out', '/tmp/out')
String classifier = argsMap.getOrDefault('classifier', null)
int ds = (argsMap.containsKey('ds') ? (argsMap.get('ds') as int) : 16)
boolean exportGeoJSON = ("" + argsMap.getOrDefault('export_geojson','true')).toBoolean()
boolean exportMask    = ("" + argsMap.getOrDefault('export_mask','true')).toBoolean()

// ---- Imports ----------------------------------------------------------------
import static qupath.lib.gui.scripting.QPEx.*
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.RegionRequest

import java.awt.image.BufferedImage
import java.awt.geom.Path2D
import java.awt.Color
import javax.imageio.ImageIO

// ---- Helpers ----------------------------------------------------------------
double safe(def x) { (x instanceof Number) ? x.doubleValue() : 0d }
double geomAreaUm2(PathAnnotationObject o, double f){
    def r = o?.getROI()
    return r == null ? 0d : r.getArea() * f
}
String sanitize(String s) { s?.replaceAll('[^A-Za-z0-9._-]', '_') ?: 'image' }

def drawPolyInto = { geom, g2, x0, y0 ->
    def putPoly = { poly ->
        def path = new Path2D.Double()
        def ext = poly.getExteriorRing().getCoordinates()
        if (ext.length > 0) {
            path.moveTo(ext[0].x - x0, ext[0].y - y0)
            for (int i=1; i<ext.length; i++)
                path.lineTo(ext[i].x - x0, ext[i].y - y0)
            path.closePath()
            g2.fill(path)
            // holes: XOR out
            for (int r=0; r<poly.getNumInteriorRing(); r++) {
                def hole = poly.getInteriorRingN(r).getCoordinates()
                def hpath = new Path2D.Double()
                if (hole.length > 0) {
                    hpath.moveTo(hole[0].x - x0, hole[0].y - y0)
                    for (int j=1; j<hole.length; j++)
                        hpath.lineTo(hole[j].x - x0, hole[j].y - y0)
                    hpath.closePath()
                    g2.setXORMode(Color.WHITE)
                    g2.fill(hpath)
                    g2.setPaintMode()
                }
            }
        }
    }
    def g = geom
    if (g.getGeometryType().toLowerCase().contains("multi")) {
        (0..<g.getNumGeometries()).each { putPoly(g.getGeometryN(it)) }
    } else {
        putPoly(g)
    }
}

// ---- Output dirs ------------------------------------------------------------
def dir     = new File(outDir); dir.mkdirs()
def docsDir = new File(dir, "documents"); docsDir.mkdirs()
def imgsDir = new File(dir, "images");    imgsDir.mkdirs()
def masksDir= new File(dir, "masks");     masksDir.mkdirs()
println "🗂  Output directory: ${dir.getAbsolutePath()}"

// ---- Image type & stains ----------------------------------------------------
setImageType('BRIGHTFIELD_H_DAB')
setColorDeconvolutionStains('''
{
  "Name":"H-DAB default",
  "Stain 1":"Hematoxylin","Values 1":"0.65111 0.70119 0.29049",
  "Stain 2":"DAB","Values 2":"0.26917 0.56824 0.77759",
  "Background":"255 255 255"
}
''')
println "🎨 Set image type & H-DAB stains."

// ---- ROIs: use existing or create whole-slide ROI ---------------------------
def rois = getAnnotationObjects().findAll{ it.getPathClass() == null }
if (rois.isEmpty()) {
  def server = getCurrentImageData().getServer()
  def w = server.getWidth(); def h = server.getHeight()
  def whole = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, w, h, null))
  addObject(whole)
  rois = [whole]
  println "🧭 No ROIs detected; created whole-slide ROI."
}
rois*.setPathClass(getPathClass('Region'))

// ---- Load classifier ---------------------------------------------------------
if (classifier != null) {
  def f = new File(classifier)
  if (!f.exists()) throw new RuntimeException("Classifier not found at: " + f.getAbsolutePath())
  loadPixelClassifier(f)
  println "🧠 Loaded classifier from file: ${f.getAbsolutePath()}"
} else {
  loadPixelClassifier('lipids (1)')
  println "🧠 Loaded classifier by name: lipids (1)"
}
def lipidClass = getPathClass('Lipids')

// ---- Clear existing Lipids detections (fresh run) ---------------------------
def existing = getDetectionObjects().findAll { it.getPathClass() == lipidClass }
if (!existing.isEmpty()) {
  removeObjects(existing, true)
  println "🧹 Cleared ${existing.size()} pre-existing Lipids detections."
}

// ---- Calibration (px²→µm²) --------------------------------------------------
def cal = getCurrentImageData().getServer().getPixelCalibration()
double px2um2 = safe(cal?.pixelWidthMicrons); if (px2um2 <= 0) px2um2 = 1d
px2um2 *= safe(cal?.pixelHeightMicrons);      if (px2um2 <= 0) px2um2 = 1d
println sprintf("📏 Calibration factor (px²→µm²): %.6f", px2um2)

// ---- Per-ROI segmentation & metrics -----------------------------------------
def hier = getCurrentHierarchy()
def counts = [], pcts = [], lipidLists = []

rois.eachWithIndex { roi, i ->
    setSelectedObject(roi)
    def base = new HashSet(getAnnotationObjects())

    createAnnotationsFromPixelClassifier('lipids (1)', 0, 0, 'SPLIT')
    fireHierarchyUpdate()

    def allNow = getAnnotationObjects()
    def lipids = allNow.findAll {
        it instanceof PathAnnotationObject &&
        it.getPathClass() == lipidClass &&
        it.getROI() != null &&
        roi.getROI().getGeometry().contains(it.getROI().getGeometry())
    }
    lipids.each { hier.addObjectBelowParent(roi, it, false); it.locked = true }

    double roiArea = geomAreaUm2(roi, px2um2)
    def areas = lipids.collect { geomAreaUm2(it, px2um2) }
    double lipidTot = (areas.isEmpty() ? 0d : areas.sum() as double)
    double pct = roiArea > 0 ? lipidTot / roiArea * 100d : 0d

    counts << lipids.size()
    pcts   << pct
    lipidLists << areas.collect { sprintf('%.2f', it) }

    println "ROI ${i+1}: area ${sprintf('%.2f', roiArea)} µm², " +
            "lipids ${lipids.size()}, lipid area ${sprintf('%.2f', lipidTot)} µm² " +
            "(${sprintf('%.2f', pct)} %)"
}

// pad to exactly 5 ROI slots
while (counts.size() < 5) { counts << 0; pcts << 0d; lipidLists << [] }

// ---- Collect all lipid annotations after creation ---------------------------
def lipidAnns = getAnnotationObjects().findAll {
    it instanceof PathAnnotationObject && it.getPathClass() == lipidClass && it.getROI() != null
}

// ---- Derive names & server ---------------------------------------------------
def imageData = getCurrentImageData()
def server    = imageData.getServer()
def imgName   = server.getMetadata().getName()
def imgSafe   = sanitize(imgName)

// ---- 1) CSVs into documents/ ------------------------------------------------
def cntCsv = new File(docsDir, 'lipid_counts_summary.csv')
def needsHeader = !cntCsv.exists()
cntCsv.withWriterAppend { w ->
    if (needsHeader) w.println("Image,ROI1,ROI2,ROI3,ROI4,ROI5,Average")
    def avg = counts.sum() / 5d
    w.println("${imgName}," + counts.join(',') + ',' + sprintf('%.2f', avg))
}
def areaCsv = new File(docsDir, "lipid_areas_${imgSafe}.csv")
areaCsv.withWriter { w ->
    w.println("ROI1,ROI2,ROI3,ROI4,ROI5")
    int maxRows = lipidLists.collect { it.size() }.max() ?: 0
    (0..<maxRows).each { r ->
        def row = []
        (0..4).each { c -> row << (r < lipidLists[c].size() ? lipidLists[c][r] : "") }
        w.println(row.join(','))
    }
}
println "📝 Wrote documents: ${cntCsv.getAbsolutePath()} , ${areaCsv.getAbsolutePath()}"

// ---- 2) JSON summary into documents/ ----------------------------------------
def sum = [
  image   : imgName,
  rois    : (1..5).collect { idx ->
              [
                index      : idx,
                count      : counts[idx-1],
                area_pct   : sprintf('%.2f', pcts[idx-1]),
                areas_um2  : lipidLists[idx-1]
              ]
            },
  totals  : [
    total_count : counts.sum(),
    mean_count  : sprintf('%.2f', counts.sum()/5d)
  ]
]
def sumJson = new File(docsDir, "summary.json")
sumJson.text = new groovy.json.JsonBuilder(sum).toPrettyString()
println "🧾 Wrote summary: ${sumJson.getAbsolutePath()}"

// ---- 3) Overview PNG into images/ -------------------------------------------
int W = server.getWidth(), H = server.getHeight()
def overview = new File(imgsDir, "${imgSafe}_overview.png")
writeImageRegion(
    imageData,
    RegionRequest.createInstance(server.getPath(), ds, 0, 0, W, H),
    overview.getAbsolutePath()
)
println "🖼  Wrote overview PNG: ${overview.getAbsolutePath()} (ds=${ds})"

// ---- 4) Per-ROI PNGs & TIFF masks -------------------------------------------
rois.eachWithIndex { roi, i ->
    def rr = RegionRequest.createInstance(server.getPath(), 1, roi.getROI())
    def roiPng = new File(imgsDir, "roi_${i+1}.png")
    writeImageRegion(imageData, rr, roiPng.getAbsolutePath())
    println "🖼  ROI ${i+1} PNG: ${roiPng.getAbsolutePath()}"

    int rw = (int)Math.round(roi.getROI().getBoundsWidth())
    int rh = (int)Math.round(roi.getROI().getBoundsHeight())
    int x0 = (int)Math.round(roi.getROI().getBoundsX())
    int y0 = (int)Math.round(roi.getROI().getBoundsY())

    if (exportMask) {
        def bi = new BufferedImage(Math.max(rw,1), Math.max(rh,1), BufferedImage.TYPE_BYTE_GRAY)
        def g2 = bi.createGraphics()
        g2.setColor(new Color(0,0,0)); g2.fillRect(0,0,bi.getWidth(), bi.getHeight())
        g2.setColor(new Color(255,255,255))

        lipidAnns.findAll { ann ->
            def g = ann.getROI().getGeometry()
            return g != null && (roi.getROI().getGeometry().intersects(g) || roi.getROI().getGeometry().contains(g))
        }.each { ann ->
            drawPolyInto(ann.getROI().getGeometry(), g2, x0, y0)
        }
        g2.dispose()

        def roiMask = new File(masksDir, "roi_${i+1}_mask.tif")
        def ok = ImageIO.write(bi, "tif", roiMask)
        if (ok) println "🧪  ROI ${i+1} mask TIFF: ${roiMask.getAbsolutePath()}"
        else    println "⚠️  Could not write TIFF mask (ImageIO plugin missing)."
    }
}

// ---- 5) GeoJSON overlays (vector) -------------------------------------------
if (exportGeoJSON) {
    import org.locationtech.jts.geom.Geometry
    import groovy.json.JsonOutput

    def feats = []
    lipidAnns.eachWithIndex { ann, idx ->
        def g = ann.getROI().getGeometry(); if (g == null) return
        def polys = []
        if (g.getGeometryType().toLowerCase().contains("multi")) {
            (0..<g.getNumGeometries()).each { k -> polys << g.getGeometryN(k) }
        } else {
            polys << g
        }
        polys.each { poly ->
            def exterior = poly.getExteriorRing().getCoordinates().collect { c -> [c.x, c.y] }
            if (exterior && exterior.first() != exterior.last()) exterior << exterior.first()
            def holes = []
            (0..<poly.getNumInteriorRing()).each { r ->
                def ring = poly.getInteriorRingN(r).getCoordinates().collect { c -> [c.x, c.y] }
                if (ring && ring.first() != ring.last()) ring << ring.first()
                holes << ring
            }
            def geom = ["type":"Polygon","coordinates":[exterior] + holes]
            def props = ["class":"Lipids","index":idx+1]
            feats << ["type":"Feature","geometry":geom,"properties":props]
        }
    }
    def fc = ["type":"FeatureCollection","features":feats]
    def gj = new File(dir, "overlays.geojson")
    gj.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(fc))
    println "🌍 Wrote GeoJSON overlay: ${gj.getAbsolutePath()} (features=${feats.size()})"
}

// ---- Finalize ----------------------------------------------------------------
fireHierarchyUpdate()
selectObjects(getDetectionObjects().findAll { it.getPathClass() == getPathClass('Lipids') })
println "🎉 Done – outputs in: ${dir.getAbsolutePath()}"
