/*
 * Copyright 2006-2015 The MZmine 2 Development Team
 * 
 * This file is part of MZmine 2.
 * 
 * MZmine 2 is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine 2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine 2; if not, write to the Free Software Foundation, Inc., 51 Franklin
 * St, Fifth Floor, Boston, MA 02110-1301 USA
 */

package io.github.msdk.featuredetection.chromatogrambuilder;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.experimental.theories.DataPoint;

import com.google.common.collect.Range;

import io.github.msdk.datamodel.featuretables.FeatureTable;
import io.github.msdk.datamodel.impl.MSDKObjectBuilder;
import io.github.msdk.datamodel.msspectra.MsSpectrumDataPointList;
import io.github.msdk.datamodel.rawdata.MsScan;
import io.github.msdk.datamodel.rawdata.RawDataFile;
import io.github.msdk.datamodel.util.DataPointSorter;
import io.github.msdk.datamodel.util.DataPointSorter.SortingDirection;
import io.github.msdk.datamodel.util.DataPointSorter.SortingProperty;
import io.github.msdk.datamodel.util.MZTolerance;

public class HighestDataPointConnector {

    private MsSpectrumDataPointList dataPoints;
    private MZTolerance mzTolerance;
    private double minimumTimeSpan, minimumHeight;

    // Mapping of last data point m/z --> chromatogram
    private Set<Chromatogram> buildingChromatograms;

    public HighestDataPointConnector(double minimumTimeSpan,
            double minimumHeight, MZTolerance mzTolerance) {

        this.mzTolerance = mzTolerance;
        this.minimumHeight = minimumHeight;
        this.minimumTimeSpan = minimumTimeSpan;

        // Create the data structure
        dataPoints = MSDKObjectBuilder.getMsSpectrumDataPointList();

        // We use LinkedHashSet to maintain a reproducible ordering. If we use
        // plain HashSet, the resulting peak list row IDs will have different
        // order every time the method is invoked.
        buildingChromatograms = new LinkedHashSet<Chromatogram>();

    }

    public void addScan(RawDataFile dataFile, MsScan scan) {

        // Load scan data points
        scan.getDataPoints(dataPoints);
        final double mzBuffer[] = dataPoints.getMzBuffer();
        final float intensityBuffer[] = dataPoints.getIntensityBuffer();

        // Sort m/z peaks by descending intensity
        DataPointSorter.sortDataPoints(mzBuffer, intensityBuffer,
                dataPoints.getSize(), SortingProperty.INTENSITY,
                SortingDirection.DESCENDING);
        

        // A set of already connected chromatograms in each iteration
        Set<Chromatogram> connectedChromatograms = new LinkedHashSet<Chromatogram>();

        // TODO: these two nested cycles should be optimized for speed
        for (DataPoint mzPeak : mzValues) {

            // Search for best chromatogram, which has the highest _last_ data point
            Chromatogram bestChromatogram = null;

            for (Chromatogram testChrom : buildingChromatograms) {

                DataPoint lastMzPeak = testChrom.getLastMzPeak();
                Range<Double> toleranceRange = mzTolerance
                        .getToleranceRange(lastMzPeak.getMZ());
                if (toleranceRange.contains(mzPeak.getMZ())) {
                    if ((bestChromatogram == null) || (testChrom.getLastMzPeak()
                            .getIntensity() > bestChromatogram.getLastMzPeak()
                                    .getIntensity())) {
                        bestChromatogram = testChrom;
                    }
                }

            }

            // If we found best chromatogram, check if it is already connected.
            // In such case, we may discard this mass and continue. If we
            // haven't found a chromatogram, we may create a new one.
            if (bestChromatogram != null) {
                if (connectedChromatograms.contains(bestChromatogram)) {
                    continue;
                }
            } else {
                bestChromatogram = new Chromatogram(dataFile);
            }

            // Add this mzPeak to the chromatogram
            bestChromatogram.addMzPeak(scanNumber, mzPeak);

            // Move the chromatogram to the set of connected chromatograms
            connectedChromatograms.add(bestChromatogram);

        }

        // Process those chromatograms which were not connected to any m/z peak
        for (Chromatogram testChrom : buildingChromatograms) {

            // Skip those which were connected
            if (connectedChromatograms.contains(testChrom)) {
                continue;
            }

            // Check if we just finished a long-enough segment
            if (testChrom.getBuildingSegmentLength() >= minimumTimeSpan) {
                testChrom.commitBuildingSegment();

                // Move the chromatogram to the set of connected chromatograms
                connectedChromatograms.add(testChrom);
                continue;
            }

            // Check if we have any committed segments in the chromatogram
            if (testChrom.getNumberOfCommittedSegments() > 0) {
                testChrom.removeBuildingSegment();

                // Move the chromatogram to the set of connected chromatograms
                connectedChromatograms.add(testChrom);
                continue;
            }

        }

        // All remaining chromatograms in buildingChromatograms are discarded
        // and buildingChromatograms is replaced with connectedChromatograms
        buildingChromatograms = connectedChromatograms;

    }

    public void finishChromatograms(FeatureTable resultTable) {

        // Iterate through current chromatograms and remove those which do not
        // contain any committed segment nor long-enough building segment

        Iterator<Chromatogram> chromIterator = buildingChromatograms.iterator();
        while (chromIterator.hasNext()) {

            Chromatogram chromatogram = chromIterator.next();

            if (chromatogram.getBuildingSegmentLength() >= minimumTimeSpan) {
                chromatogram.commitBuildingSegment();
                chromatogram.finishChromatogram();
            } else {
                if (chromatogram.getNumberOfCommittedSegments() == 0) {
                    chromIterator.remove();
                    continue;
                } else {
                    chromatogram.removeBuildingSegment();
                    chromatogram.finishChromatogram();
                }
            }

            // Remove chromatograms below minimum height
            if (chromatogram.getHeight() < minimumHeight)
                chromIterator.remove();

        }

        // All remaining chromatograms are good, so we can return them
        Chromatogram[] chromatograms = buildingChromatograms
                .toArray(new Chromatogram[0]);
        return chromatograms;
    }

}