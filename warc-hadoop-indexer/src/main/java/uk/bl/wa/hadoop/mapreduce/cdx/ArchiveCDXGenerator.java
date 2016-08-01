package uk.bl.wa.hadoop.mapreduce.cdx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.lib.InputSampler;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.partition.TotalOrderPartitioner;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.archive.hadoop.mapreduce.AlphaPartitioner;

import uk.bl.wa.hadoop.mapreduce.io.TextOutputFormat;
import uk.bl.wa.hadoop.mapreduce.lib.ArchiveToCDXFileInputFormat;
import uk.bl.wa.hadoop.mapreduce.lib.DereferencingArchiveToCDXRecordReader;

/**
 * @author rcoram
 * 
 */

@SuppressWarnings("static-access")
public class ArchiveCDXGenerator extends Configured implements Tool {

    private static final Log log = LogFactory.getLog(ArchiveCDXGenerator.class);

    private String inputPath;
    private String outputPath;
    private String splitFile;
    private String cdxFormat;
    private boolean hdfs;
    private boolean wait;
    private int numReducers;
    private String metaTag = "-";
    private String cdxserver = null;
    private int cdxserver_batch_size = 100;

    private void setup(String args[], Configuration conf)
            throws ParseException, URISyntaxException {
        Options options = new Options();
        options.addOption("i", true, "input file list");
        options.addOption("o", true, "output directory");
        options.addOption("s", true, "split file");
        options.addOption("c", true, "CDX format");
        options.addOption("h", false, "HDFS refs.");
        options.addOption("w", false, "Wait for completion.");
        options.addOption("r", true, "Num. Reducers");
        options.addOption("a", true, "ARK identifier lookup");
        options.addOption("m", true,
                "Meta-tag character (we use 'O' for open, 'L' for LD, 'X' for exclude.)");
        options.addOption(OptionBuilder.withArgName("property=value").hasArgs(2)
                .withValueSeparator()
                .withDescription("use value for given property").create("D"));
        options.addOption("t", true,
                "TinyCDXServer endpoint to use, e.g. 'http://localhost:8080/collection'.");
        options.addOption("B", true,
                "Batch size to use when POSTing to a TinyCDXServer, defaults to '1000'.");

        CommandLineParser parser = new PosixParser();
        CommandLine cmd = parser.parse(options, args);
        this.inputPath = cmd.getOptionValue("i");
        this.outputPath = cmd.getOptionValue("o");
        this.splitFile = cmd.getOptionValue("s");
        this.cdxFormat = cmd.getOptionValue("c",
                DereferencingArchiveToCDXRecordReader.CDX_11);
        this.hdfs = cmd.hasOption("h");
        this.wait = cmd.hasOption("w");
        this.numReducers = Integer.parseInt(cmd.getOptionValue("r"));
        if (cmd.hasOption("a")) {
            URI lookup = new URI(cmd.getOptionValue("a"));
            System.out.println("Adding ARK lookup: " + lookup);
            DistributedCache.addCacheFile(lookup, conf);
        }
        if (inputPath == null || outputPath == null) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("ArchiveCDXGenerator", options);
            System.exit(1);
        }
        if (cmd.hasOption("m")) {
            metaTag = cmd.getOptionValue("m");
        }
        if (cmd.hasOption("t")) {
            this.cdxserver = cmd.getOptionValue("t");
            this.cdxserver_batch_size = Integer
                    .parseInt(cmd.getOptionValue("B", "1000"));
            this.cdxFormat = DereferencingArchiveToCDXRecordReader.CDX_11;
        }
    }

    private static long getNumMapTasks(Path split, Configuration conf)
            throws IOException {
        FileSystem fs = split.getFileSystem(conf);
        FSDataInputStream input = fs.open(split);
        BufferedReader br = new BufferedReader(new InputStreamReader(input));
        long lineCount = 0L;
        while (br.readLine() != null) {
            lineCount++;
        }
        input.close();
        if (lineCount < 3)
            return 1;
        else
            return (lineCount / 3);
    }

    protected Job createJob(String[] args)
            throws Exception {

        Job job = new Job();
        job.setJobName(
                "ArchiveCDXGenerator" + "_" + System.currentTimeMillis());

        Configuration conf = job.getConfiguration();

        this.setup(args, conf);

        Path input = new Path(this.inputPath);
        FileInputFormat.addInputPath(job, input);
        Path outputPath = new Path(this.outputPath);
        FileOutputFormat.setOutputPath(job, outputPath);
        job.setInputFormatClass(ArchiveToCDXFileInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);
        conf.set("map.output.key.field.separator", "");
        conf.set("cdx.format", this.cdxFormat);
        conf.set("cdx.hdfs", Boolean.toString(this.hdfs));
        conf.set("cdx.metatag", this.metaTag);
        conf.set("mapred.map.tasks.speculative.execution", "false");
        conf.set("mapred.reduce.tasks.speculative.execution", "false");

        // POST directly to the tinycdxserver:
        if (this.cdxserver != null) {
            conf.set("tinycdxserver.endpoint", this.cdxserver);
            conf.setInt("tinycdxserver.batch_size", this.cdxserver_batch_size);
            job.setMapperClass(TinyCDXServerMapper.class);
        } else {
            // Default to the pass-through mapper:
            job.setMapperClass(Mapper.class);
        }

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        if (this.splitFile != null) {
            log.info("Setting splitFile to " + this.splitFile);
            AlphaPartitioner.setPartitionPath(conf, this.splitFile);
            job.setPartitionerClass(AlphaPartitioner.class);
        } else {
            job.setPartitionerClass(TotalOrderPartitioner.class);
            TotalOrderPartitioner.setPartitionFile(job.getConfiguration(),
                    new Path(outputPath, "_partitions.lst"));
            // FIXME This probably won't work - need to update to recent API
            JobConf jc = new JobConf(conf);
            InputSampler.writePartitionFile(jc,
                    new InputSampler.RandomSampler(1, 10000));
        }
        job.setReducerClass(Reducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(this.numReducers);
        job.setJarByClass(ArchiveCDXGenerator.class);

        FileSystem fs = input.getFileSystem(conf);
        FileStatus inputStatus = fs.getFileStatus(input);
        FileInputFormat.setMaxInputSplitSize(job, inputStatus.getLen()
                / getNumMapTasks(new Path(this.inputPath), conf));
        return job;
    }


    @Override
    public int run(String[] args) throws Exception {

        Job job = createJob(args);

        job.submit();
        if (this.wait)
            job.waitForCompletion(true);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        ArchiveCDXGenerator cdx = new ArchiveCDXGenerator();
        int ret = ToolRunner.run(cdx, args);
        System.exit(ret);
    }
}
