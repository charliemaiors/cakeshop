package com.jpmorgan.cakeshop.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmorgan.cakeshop.util.FileUtils;
import com.jpmorgan.cakeshop.util.SortedProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jmx.export.annotation.AnnotationMBeanExporter;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig implements AsyncConfigurer {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AppConfig.class);

    public static final String CONFIG_FILE = "application.properties";
    public static final String ACCOUNT_FILE = "accounts.json";

    /**
     * Return the configured environment name (via spring.profiles.active system prop)
     *
     * @return String Environment name
     */
    public static String getEnv() {
        return System.getProperty("spring.profiles.active");
    }

    /**
     * Return the configured config location
     * <p>
     * Search order:
     * - ETH_CONFIG environment variable
     * - eth.config.dir system property (-Deth.config.dir param)
     * - Detect tomcat (container) root relative to classpath
     *
     * @return
     */
    public static String getConfigPath() {
        String configPath = getProp("ETH_CONFIG", "eth.config.dir");
        if (!StringUtils.isBlank(configPath)) {
            return FileUtils.expandPath(configPath, getEnv());
        }

        String webappRoot = FileUtils.expandPath(FileUtils.getClasspathPath(""), "..", "..");
        String tomcatRoot = FileUtils.expandPath(webappRoot, "..", "..");

        // migrate conf dir to new name
        File oldPath = new File(FileUtils.expandPath(tomcatRoot, "data", "enterprise-ethereum"));
        File newPath = new File(FileUtils.expandPath(tomcatRoot, "data", "cakeshop"));
        if (oldPath.exists() && !newPath.exists()) {
            oldPath.renameTo(newPath);
        }

        return FileUtils.expandPath(tomcatRoot, "data", "cakeshop", getEnv());
    }

    private static String getProp(String env, String java) {
        String str = System.getenv(env);
        if (StringUtils.isBlank(str)) {
            str = System.getProperty(java);
        }
        return str;
    }

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() throws IOException {
        return createPropConfigurer(getConfigPath());
    }

    public static Map<String, String> getAccountsConfigurationRemote() throws IOException {
        return getAccountUnlockConfiguration(getConfigPath());
    }

    public static String getVendorConfigFile() {
        return "config".concat(File.separator).concat("application.properties");
    }

    public static String getVendorEnvConfigFile() {
        return "config".concat(File.separator).concat("application-").concat(getEnv()).concat(".properties");
    }

    public static void initVendorConfig(File configFile) throws IOException {
        // copy default file
        String path = FileUtils.getClasspathPath(getVendorEnvConfigFile()).toString();
        LOG.info("Initializing new config from " + path);

        // defaults + env defaults
        Properties mergedProps = new Properties();
        mergedProps.load(FileUtils.getClasspathStream(getVendorConfigFile()));
        mergedProps.load(FileUtils.getClasspathStream(getVendorEnvConfigFile()));

        SortedProperties.store(mergedProps, new FileOutputStream(configFile));
    }

    public static PropertySourcesPlaceholderConfigurer createPropConfigurer(String configDir)
            throws IOException {

        if (StringUtils.isBlank(getEnv())) {
            throw new IOException("System property 'spring.profiles.active' not set; unable to load config");
        }

        LOG.info("eth.config.dir=" + configDir);

        File configPath = new File(configDir);
        File configFile = new File(configPath.getPath() + File.separator + CONFIG_FILE);

        if (!configPath.exists() || !configFile.exists()) {
            LOG.debug("Config dir does not exist, will init");

            configPath.mkdirs();
            if (!configPath.exists()) {
                throw new IOException("Unable to create config dir: " + configPath.getAbsolutePath());
            }

            initVendorConfig(configFile);

        } else {
            Properties mergedProps = new Properties();
            mergedProps.load(FileUtils.getClasspathStream(getVendorEnvConfigFile()));
            mergedProps.load(new FileInputStream(configFile)); // overwrite vendor props with our configs
            SortedProperties.store(mergedProps, new FileOutputStream(configFile));
        }

        // Finally create the configurer and return it
        Properties localProps = new Properties();
        localProps.setProperty("config.path", configPath.getPath());

        PropertySourcesPlaceholderConfigurer propConfig = new PropertySourcesPlaceholderConfigurer();
        propConfig.setLocation(new FileSystemResource(configFile));
        propConfig.setProperties(localProps);
        propConfig.setLocalOverride(true);

        LOG.info("Loading config from " + configFile.toString());

        if (System.getProperty("spring.profiles.active").equals("remote")) {
            Path src = Paths.get(FileUtils.expandPath(SystemUtils.USER_DIR, "data", "geth", "remote", "accounts.json"));
            Path dest = Paths.get(AppConfig.getConfigPath());
            try {
                FileUtils.copyFileToDirectory(src.toFile(), dest.toFile());
            } catch (IOException e) {
                System.out.println(new StringBuilder("Exception on copy ").append(e.getMessage()).toString());
            }
            String content = new String(Files.readAllBytes(Paths.get(AppConfig.getConfigPath(), "accounts.json")));
            LOG.debug("File content is {}", content);
        }

        return propConfig;
    }

    @Bean(name = "asyncExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(10);
        exec.setMaxPoolSize(500);
        exec.setQueueCapacity(2000);
        exec.setThreadNamePrefix("cake-");
        exec.afterPropertiesSet();
        return exec;
    }

    @Bean
    public AnnotationMBeanExporter annotationMBeanExporter() {
        AnnotationMBeanExporter annotationMBeanExporter = new AnnotationMBeanExporter();
        annotationMBeanExporter.addExcludedBean("dataSource");
        return annotationMBeanExporter;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }

    private static Map<String, String> getAccountUnlockConfiguration(String configDir) throws IOException {
        File configPath = new File(configDir);
        File configFile = new File(configPath.getPath() + File.separator + ACCOUNT_FILE);

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(configFile, new TypeReference<Map<String, String>>() {
        });
    }


}
