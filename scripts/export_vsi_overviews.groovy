import qupath.lib.images.servers.ImageServers
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import java.awt.image.BufferedImage

def input = System.getenv('IFQ_VSI_INPUT')
def output = System.getenv('IFQ_VSI_OUTPUT')
double targetMax = Double.parseDouble(System.getenv('IFQ_VSI_TARGET_MAX') ?: '7500')
if (!input || !output) throw new IllegalArgumentException('IFQ_VSI_INPUT and IFQ_VSI_OUTPUT are required')
def outDir = new File(output); outDir.mkdirs()

def percentileRange = { raster, int band, int w, int h ->
    int step = Math.max(1, (int)Math.ceil(Math.sqrt((w * (double)h) / 900000.0d)))
    long[] hist = new long[65536]; long n = 0
    for (int y=0; y<h; y+=step) for (int x=0; x<w; x+=step) {
        int v = Math.max(0, Math.min(65535, raster.getSample(x,y,band))); hist[v]++; n++
    }
    def find = { double q ->
        long target = Math.max(1L, Math.round(n*q)); long c=0
        for (int i=0; i<hist.length; i++) { c += hist[i]; if (c >= target) return i }
        return 65535
    }
    int lo=find(0.005d), hi=find(0.998d); if (hi<=lo) hi=Math.min(65535,lo+1)
    [lo,hi]
}
def stretch = { int v, range ->
    double z=(v-range[0])/(double)(range[1]-range[0]); z=Math.max(0d,Math.min(1d,z))
    (int)Math.round(255d*Math.pow(z,0.85d))
}
def writeJpeg = { BufferedImage image, File file ->
    def writer=ImageIO.getImageWritersByFormatName('jpeg').next(); def ios=new FileImageOutputStream(file)
    try { writer.output=ios; def p=writer.defaultWriteParam; p.compressionMode=ImageWriteParam.MODE_EXPLICIT; p.compressionQuality=0.96f; writer.write(null,new IIOImage(image,null,null),p) }
    finally { writer.dispose(); ios.close() }
}

def files=new File(input).listFiles().findAll{it.name.toLowerCase().endsWith('.vsi')}.sort{it.name}
files.eachWithIndex { f, index ->
    def stem=f.name.replaceFirst(/(?i)\.vsi$/,'').replaceAll(/[^A-Za-z0-9._-]+/,'_')
    println "[${index+1}/${files.size()}] ${f.name}"
    def server=ImageServers.buildServer(f.toURI(),'--series','2')
    try {
        int fw=server.width, fh=server.height; double ds=Math.max(1d,Math.max(fw,fh)/targetMax)
        def raw=server.readRegion(ds,0,0,fw,fh); def ras=raw.raster; int w=raw.width,h=raw.height,nc=ras.numBands
        if(nc<4) throw new IllegalStateException("Expected four bands, found ${nc}")
        def ranges=(0..<4).collect{percentileRange(ras,it,w,h)}
        println "  native=${fw}x${fh} export=${w}x${h} ds=${ds} ranges=${ranges}"
        def comp=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); int[] rgb=new int[w*h]
        def dapi=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY)
        int p=0
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int b=stretch(ras.getSample(x,y,0),ranges[0]); int g=stretch(ras.getSample(x,y,1),ranges[1])
            int r=stretch(ras.getSample(x,y,2),ranges[2]); int wh=stretch(ras.getSample(x,y,3),ranges[3])
            rgb[p++]=(Math.min(255,r+wh)<<16)|(Math.min(255,g+wh)<<8)|Math.min(255,b+wh)
            dapi.raster.setSample(x,y,0,b)
        }
        comp.setRGB(0,0,w,h,rgb,0,w)
        def compositeFile=new File(outDir,stem+'__WSI_composite.jpg'); def dapiFile=new File(outDir,stem+'__WSI_DAPI.png')
        writeJpeg(comp,compositeFile); ImageIO.write(dapi,'PNG',dapiFile)
        new File(outDir,stem+'__WSI_export.txt').text="source=${f.absolutePath}\nseries=2\nnative=${fw}x${fh}\nexport=${w}x${h}\ndownsample=${ds}\nchannels=DAPI blue; FITC green; Cy3 red; Cy5 white\nranges=${ranges}\n"
    } finally { server.close() }
}
println "Exported ${files.size()} WSI overviews to ${outDir}"
