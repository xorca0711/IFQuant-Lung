import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.formats.meta.IMetadata
import ome.units.UNITS

def input = System.getenv('IFQ_VSI_INPUT')
if (!input) throw new IllegalArgumentException('IFQ_VSI_INPUT is required')

def files = new File(input).listFiles().findAll { it.name.toLowerCase().endsWith('.vsi') }.sort { it.name }
files.each { f ->
    def r = new ImageReader()
    r.setFlattenedResolutions(false)
    IMetadata m = MetadataTools.createOMEXMLMetadata()
    r.setMetadataStore(m)
    r.setId(f.absolutePath)
    println "FILE=${f.name} SERIES=${r.seriesCount}"
    for (int s = 0; s < r.seriesCount; s++) {
        r.setSeries(s); r.setResolution(0)
        def px = m.getPixelsPhysicalSizeX(s)
        def um = px == null ? '' : px.value(UNITS.MICROMETER).doubleValue()
        def names = (0..<r.effectiveSizeC).collect { c ->
            def n = m.getChannelName(s, c)
            n == null ? '' : n
        }
        println "  S=${s} W=${r.sizeX} H=${r.sizeY} C=${r.effectiveSizeC} Z=${r.sizeZ} T=${r.sizeT} RES=${r.resolutionCount} UM=${um} CHANNELS=${names}"
    }
    r.close()
}
