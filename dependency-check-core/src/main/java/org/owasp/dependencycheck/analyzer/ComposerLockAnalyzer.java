/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2015 The OWASP Foundation. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.data.composer.ComposerDependency;
import org.owasp.dependencycheck.data.composer.ComposerException;
import org.owasp.dependencycheck.data.composer.ComposerLockParser;
import org.owasp.dependencycheck.dependency.Confidence;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.utils.Checksum;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.owasp.dependencycheck.dependency.EvidenceType;

/**
 * Used to analyze a composer.lock file for a composer PHP app.
 *
 * @author colezlaw
 */
@Experimental
public class ComposerLockAnalyzer extends AbstractFileTypeAnalyzer {

    /**
     * A descriptor for the type of dependencies processed or added by this
     * analyzer
     */
    public static final String DEPENDENCY_ECOSYSTEM = "Composer";

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ComposerLockAnalyzer.class);

    /**
     * The analyzer name.
     */
    private static final String ANALYZER_NAME = "Composer.lock analyzer";

    /**
     * composer.json.
     */
    private static final String COMPOSER_LOCK = "composer.lock";

    /**
     * The FileFilter.
     */
    private static final FileFilter FILE_FILTER = FileFilterBuilder.newInstance().addFilenames(COMPOSER_LOCK).build();

    /**
     * Returns the FileFilter.
     *
     * @return the FileFilter
     */
    @Override
    protected FileFilter getFileFilter() {
        return FILE_FILTER;
    }

    /**
     * Initializes the analyzer.
     *
     * @param engine a reference to the dependency-check engine
     * @throws InitializationException thrown if an exception occurs getting an
     * instance of SHA1
     */
    @Override
    protected void prepareFileTypeAnalyzer(Engine engine) throws InitializationException {
        try {
            getSha1MessageDigest();
        } catch (IllegalStateException ex) {
            setEnabled(false);
            throw new InitializationException("Unable to create SHA1 MessageDigest", ex);
        }
    }

    /**
     * Entry point for the analyzer.
     *
     * @param dependency the dependency to analyze
     * @param engine the engine scanning
     * @throws AnalysisException if there's a failure during analysis
     */
    @Override
    protected void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        try (FileInputStream fis = new FileInputStream(dependency.getActualFile())) {
            final ComposerLockParser clp = new ComposerLockParser(fis);
            LOGGER.debug("Checking composer.lock file {}", dependency.getActualFilePath());
            clp.process();
            //if dependencies are found in the lock, then there is always an empty shell dependency left behind for the
            //composer.lock. The first pass through, reuse the top level dependency, and add new ones for the rest.
            boolean processedAtLeastOneDep = false;
            for (ComposerDependency dep : clp.getDependencies()) {
                final Dependency d = new Dependency(dependency.getActualFile());
                final String filePath = String.format("%s:%s/%s/%s", dependency.getFilePath(), dep.getGroup(), dep.getProject(), dep.getVersion());
                d.setName(dep.getProject());
                d.setVersion(dep.getVersion());
                d.setEcosystem(DEPENDENCY_ECOSYSTEM);
                final MessageDigest sha1 = getSha1MessageDigest();
                d.setFilePath(filePath);
                d.setSha1sum(Checksum.getHex(sha1.digest(filePath.getBytes(Charset.defaultCharset()))));
                d.addEvidence(EvidenceType.VENDOR, COMPOSER_LOCK, "vendor", dep.getGroup(), Confidence.HIGHEST);
                d.addEvidence(EvidenceType.PRODUCT, COMPOSER_LOCK, "product", dep.getProject(), Confidence.HIGHEST);
                d.addEvidence(EvidenceType.VERSION, COMPOSER_LOCK, "version", dep.getVersion(), Confidence.HIGHEST);
                LOGGER.debug("Adding dependency {}", d.getDisplayFileName());
                engine.addDependency(d);
                //make sure we only remove the main dependency if we went through this loop at least once.
                processedAtLeastOneDep = true;
            }
            // remove the dependency at the end because it's referenced in the loop itself.
            // double check the name to be sure we only remove the generic entry.
            if (processedAtLeastOneDep && dependency.getDisplayFileName().equalsIgnoreCase("composer.lock")) {
                LOGGER.debug("Removing main redundant dependency {}", dependency.getDisplayFileName());
                engine.removeDependency(dependency);
            }
        } catch (IOException ex) {
            LOGGER.warn("Error opening dependency {}", dependency.getActualFilePath());
        } catch (ComposerException ce) {
            LOGGER.warn("Error parsing composer.json {}", dependency.getActualFilePath(), ce);
        }
    }

    /**
     * Gets the key to determine whether the analyzer is enabled.
     *
     * @return the key specifying whether the analyzer is enabled
     */
    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_COMPOSER_LOCK_ENABLED;
    }

    /**
     * Returns the analyzer's name.
     *
     * @return the analyzer's name
     */
    @Override
    public String getName() {
        return ANALYZER_NAME;
    }

    /**
     * Returns the phase this analyzer should run under.
     *
     * @return the analysis phase
     */
    @Override
    public AnalysisPhase getAnalysisPhase() {
        return AnalysisPhase.INFORMATION_COLLECTION;
    }

    /**
     * Returns the sha1 message digest.
     *
     * @return the sha1 message digest
     */
    private MessageDigest getSha1MessageDigest() {
        try {
            return MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error(e.getMessage());
            throw new IllegalStateException("Failed to obtain the SHA1 message digest.", e);
        }
    }
}
