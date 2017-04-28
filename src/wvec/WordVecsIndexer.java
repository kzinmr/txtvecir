/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package wvec;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.Clusterable;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.apache.lucene.util.packed.DirectReader;

/**
 *
 * @author Debasis
 */

class CosineDistance implements DistanceMeasure {

    double norm(double[] vec) {
        // calculate and store
        double sum = 0;
        for (int i = 0; i < vec.length; i++) {
            sum += vec[i]*vec[i];
        }
        return Math.sqrt(sum);
    }
    
    @Override
    public double compute(double[] a, double[] b) throws DimensionMismatchException {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i]*b[i];
        }
		double cosineSim = sum/(norm(a)*norm(b));
        return Math.acos(cosineSim); // return cos inverse 
    }    
}

public class WordVecsIndexer {
    IndexWriter writer;
    Properties prop;
    String indexPath;
    
    static final public String FIELD_WORD_NAME = "wordname";
    static final public String FIELD_WORD_VEC = "wordvec";

    public WordVecsIndexer(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));                
        indexPath = prop.getProperty("wvecs.index");        
    }

    public void writeIndex() throws Exception {
        
        IndexWriterConfig iwcfg = new IndexWriterConfig(
                Version.LUCENE_4_9,
                new WhitespaceAnalyzer(Version.LUCENE_4_9));
        iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        writer = new IndexWriter(FSDirectory.open(new File(indexPath)), iwcfg);        
        
        String fileToRead = prop.getProperty("wvecs.txt");
        indexFile(new File(fileToRead));
        writer.close();
        
        storeClusterInfo();
    }
    
    Document constructDoc(String id, String line) throws Exception {        
        Document doc = new Document();
        doc.add(new Field(FIELD_WORD_NAME, id, Field.Store.YES, Field.Index.NOT_ANALYZED));        
        doc.add(new Field(FIELD_WORD_VEC, line,
                Field.Store.YES, Field.Index.NOT_ANALYZED, Field.TermVector.NO));
        return doc;        
    }

    void storeClusterInfo() throws Exception {
        int numClusters = Integer.parseInt(prop.getProperty("wvecs.numclusters", "5"));        
        String clusterInfoBaseDir = prop.getProperty("wvecs.clusterids.basedir");
        String clusterInfoDirPath = clusterInfoBaseDir + "/" + numClusters;
        
        File clusterInfoFile = new File(clusterInfoDirPath);
        if (clusterInfoFile.isDirectory() && clusterInfoFile.exists()) {
            System.out.println("Cluster info already exists...");
            return;
        }

        // Create the directory...
        clusterInfoFile.mkdir();
        IndexWriterConfig iwcfg = new IndexWriterConfig(
                Version.LUCENE_4_9,
                new WhitespaceAnalyzer(Version.LUCENE_4_9));
        iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        IndexWriter clusterInfoFileWriter = new IndexWriter(FSDirectory.open(clusterInfoFile), iwcfg);        
        clusterWordVecs(clusterInfoFileWriter, numClusters);
        clusterInfoFileWriter.close();        
    }
    
    void clusterWordVecs(IndexWriter clusterIndexWriter, int numClusters) throws Exception {
        // Index where word vectors are stored
        IndexReader reader = DirectoryReader.open(FSDirectory.open(new File(indexPath)));
        int numDocs = reader.numDocs();
		//+++DG: Cluster on cosine distances, i.e. the angles between the vecs
        //KMeansPlusPlusClusterer<WordVec> clusterer = new KMeansPlusPlusClusterer<>(numClusters); 
        DistanceMeasure angleMeasure = new CosineDistance();
        KMeansPlusPlusClusterer<WordVec> clusterer = new KMeansPlusPlusClusterer<>(numClusters, 1000, angleMeasure);
		//---DG:
        List<WordVec> wordList = new ArrayList<>(numDocs);
        
        // Read every wvec and load in memory
        for (int i = 0; i < numDocs; i++) {
			System.out.println("Reading doc " + i);
            Document doc = reader.document(i);
            WordVec wvec = new WordVec(doc.get(FIELD_WORD_VEC));
            wordList.add(wvec);            
        }
        
        // Call K-means clustering
        System.out.println("Clustering the entire vocabulary...");
        List<CentroidCluster<WordVec>> clusters = clusterer.cluster(wordList);        
        
        // Save the cluster info
        System.out.println("Writing out cluster ids in Lucene index...");
        int clusterId = 0;
        for (CentroidCluster<WordVec> c : clusters) {
            List<WordVec> pointsInThisClusuter = c.getPoints();
            for (WordVec thisPoint: pointsInThisClusuter) {
                Document clusterInfo = constructDoc(thisPoint.word, String.valueOf(clusterId));
                clusterIndexWriter.addDocument(clusterInfo);
            }
            clusterId++;
        }
        
        reader.close();
    }
    
    void indexFile(File file) throws Exception {
        FileReader fr = new FileReader(file);
        BufferedReader br = new BufferedReader(fr);
        String line;
        
        final int batchSize = 10000;
        int count = 0;
        
        // Each line is word vector
        while ((line = br.readLine()) != null) {
            
            int firstSpaceIndex = line.indexOf(" ");
            String id = line.substring(0, firstSpaceIndex);
            Document luceneDoc = constructDoc(id, line);
            
            if (count%batchSize == 0) {
                System.out.println("Added " + count + " words...");
            }
            
            writer.addDocument(luceneDoc);
            count++;
        }
        br.close();
        fr.close();
    }
    
    /* Use this to index the output of word2vec, i.e. instead
       of loading the word vectors from an in-memory hashmap
       keyed by a word, use Lucene search to retrieve the vector
       given a word. This makes it possible to run this on
       a limited memory environment. */
    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[1];
            System.out.println("Usage: java WordVecsIndexer <prop-file>");
            args[0] = "init.properties";
        }

        try {
            WordVecsIndexer wvIndexer = new WordVecsIndexer(args[0]);
            //wvIndexer.writeIndex();
            wvIndexer.storeClusterInfo();
        }
        catch (Exception ex) { ex.printStackTrace(); }
    }
    
}
