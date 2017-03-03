package com.aidos.iri;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.iri.conf.AidosConfiguration;
import com.aidos.iri.conf.AidosConfiguration.DefaultConfSettings;
import com.aidos.iri.service.AidosAPI;
import com.aidos.iri.service.AidosNode;
import com.aidos.iri.service.AidosTipsManager;
import com.aidos.iri.service.storage.AidosStorage;
import com.sanityinc.jargs.CmdLineParser;
import com.sanityinc.jargs.CmdLineParser.Option;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.util.StatusPrinter;

/**
 * Main AIDOS Reference Implementation starting class
 */
public class Aidos {

    private static final Logger log = LoggerFactory.getLogger(Aidos.class);

    public static final String NAME = "IRI";
    public static final String VERSION = "1.1.2.4";

    public static void main(final String[] args) {

        log.info("Welcome to {} {}", NAME, VERSION);
        validateParams(args);
        shutdownHook();

        if (!AidosConfiguration.booling(DefaultConfSettings.HEADLESS)) {
            showAIDOSLogo();
        }

        try {

            AidosStorage.instance().init();
            AidosNode.instance().init();
            AidosTipsManager.instance().init();
            AidosAPI.instance().init();

        } catch (final Exception e) {
            log.error("Exception during AIDOS node initialisation: ", e);
            System.exit(-1);
        }
        log.info("AIDOS Node initialised correctly.");
    }

    private static void validateParams(final String[] args) {

        if (args == null || args.length < 2) {
            log.error("Invalid arguments list. Provide Api port number (i.e. '-p 14265').");
            printUsage();
        }

        final CmdLineParser parser = new CmdLineParser();

        final Option<String> port = parser.addStringOption('p', "port");
        final Option<String> rport = parser.addStringOption('r', "receiver-port");
        final Option<String> cors = parser.addStringOption('c', "enabled-cors");
        final Option<Boolean> headless = parser.addBooleanOption("headless");
        final Option<Boolean> debug = parser.addBooleanOption('d', "debug");
        final Option<Boolean> remote = parser.addBooleanOption("remote");
        final Option<String> remoteLimitApi = parser.addStringOption("remote-limit-api");
        final Option<String> neighbors = parser.addStringOption('n', "neighbors");
        final Option<Boolean> experimental = parser.addBooleanOption('e', "experimental");
        final Option<Boolean> help = parser.addBooleanOption('h', "help");

        try {
            parser.parse(args);
        } catch (CmdLineParser.OptionException e) {
            log.error("CLI error: ", e);
            printUsage();
            System.exit(2);
        }

        // mandatory args
        final String cport = parser.getOptionValue(port);
        if (cport == null) {
            log.error("Invalid arguments list. Provide at least 1 neighbor with -n or --neighbors '<list>'");
            printUsage();
        }
        AidosConfiguration.put(DefaultConfSettings.API_PORT, cport);

        // optional flags
        if (parser.getOptionValue(help) != null) {
            printUsage();
        }

        String cns = parser.getOptionValue(neighbors);
        if (cns == null) {
            log.warn("No neighbor has been specified. Server starting nodeless.");
            cns = StringUtils.EMPTY;
        }
        AidosConfiguration.put(DefaultConfSettings.NEIGHBORS, cns);


        final String vcors = parser.getOptionValue(cors);
        if (vcors != null) {
            log.debug("Enabled CORS with value : {} ", vcors);
            AidosConfiguration.put(DefaultConfSettings.CORS_ENABLED, vcors);
        }

        final String vremoteapilimit = parser.getOptionValue(remoteLimitApi);
        if (vremoteapilimit != null) {
            log.debug("The following api calls are not allowed : {} ", vremoteapilimit);
            AidosConfiguration.put(DefaultConfSettings.REMOTEAPILIMIT, vremoteapilimit);
        }

        final String vrport = parser.getOptionValue(rport);
        if (vrport != null) {
            AidosConfiguration.put(DefaultConfSettings.TANGLE_RECEIVER_PORT, vrport);
        }

        if (parser.getOptionValue(headless) != null) {
            AidosConfiguration.put(DefaultConfSettings.HEADLESS, "true");
        }

        if (parser.getOptionValue(remote) != null) {
            log.info("Remote access enabled. Binding API socket to listen any interface.");
            AidosConfiguration.put(DefaultConfSettings.API_HOST, "0.0.0.0");
        }

        if (parser.getOptionValue(experimental) != null) {
            log.info("Experimental AIDOS features turned on.");
            AidosConfiguration.put(DefaultConfSettings.EXPERIMENTAL, "true");
        }

        if (Integer.parseInt(cport) < 1024) {
            log.warn("Warning: api port value seems too low.");
        }

        if (parser.getOptionValue(debug) != null) {
            AidosConfiguration.put(DefaultConfSettings.DEBUG, "true");
            log.info(AidosConfiguration.allSettings());
            StatusPrinter.print((LoggerContext) LoggerFactory.getILoggerFactory());
        }
    }

    private static void printUsage() {
        log.info("Usage: java -jar {}-{}.jar " +
                "[{-p,--port} 14265] " +
                "[{-r,--receiver-port} 14265] " +
                "[{-c,--enabled-cors} *] " +
                "[{-h}] [{--headless}] " +
                "[{-d,--debug}] " +
                "[{-e,--experimental}]" +
                "[{--remote}]" +
                // + "[{-t,--testnet} false] " // -> TBDiscussed (!)
                "[{-n,--neighbors} '<list of neighbors>'] ", NAME, VERSION);
        System.exit(0);
    }

    private static void shutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            log.info("Shutting down AIDOS node, please hold tight...");
            try {

                AidosAPI.instance().shutDown();
                AidosTipsManager.instance().shutDown();
                AidosNode.instance().shutdown();
                AidosStorage.instance().shutdown();

            } catch (final Exception e) {
                log.error("Exception occurred shutting down AIDOS node: ", e);
            }
        }, "Shutdown Hook"));
    }

    private static void showAIDOSLogo() {
        final String charset = "UTF8";

        try {
            final Path path = Paths.get("logo.utf8.ans");
            Files.readAllLines(path, Charset.forName(charset)).forEach(log::info);
        } catch (IOException e) {
            log.error("Impossible to display logo. Charset {} not supported by terminal.", charset);
        }
    }
}
