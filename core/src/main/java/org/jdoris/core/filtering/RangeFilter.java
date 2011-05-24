package org.jdoris.core.filtering;

import org.apache.log4j.Logger;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jdoris.core.Ellipsoid;
import org.jdoris.core.Orbit;
import org.jdoris.core.SLCImage;
import org.jdoris.core.Window;
import org.jdoris.core.todo_classes.todo_classes;
import org.jdoris.core.utils.*;

import static org.jdoris.core.utils.LinearAlgebraUtils.*;

public class RangeFilter {

    static Logger logger = Logger.getLogger(RangeFilter.class.getName());

    //TODO: make template classes for generalInput, operatorInput, and ProductMetadata class
/*
    public static void rangefilter(final todo_classes.inputgeneral input_gen,
                                   final SLCImage master,
                                   final SLCImage slave,
                                   final todo_classes.productinfo interferogram,
                                   final todo_classes.input_filtrange inputfiltrange) {
    }
*/

    /**
     * filterblock
     * Computes powerspectrum of complex interferogram.
     * (Product oversampled master. conj oversampled slave in range)
     * A peak in this spectrum corresponds to frequency shift.
     * The master and slave are LPF filtered for this shift.
     * <p/>
     * Optionally the oversampling can be turned off, since no use if only small baseline, and flat terrain.
     * <p/>
     * The powerspectrum can be weighted to give more influence to higher frequencies (conv. of 2 blocks, should be hamming).
     * <p/>
     * The peak is detected by taking a mean of nlMean lines (odd).
     * <p/>
     * Filtering is applied if the SNR (N*power peak / power rest) is above a user supplied threshold.
     * At LPF filtering of the master/slave a hamming window may be applied first to deweight, then to re-weight the spectrum
     * <p/>
     * Should filter based on zero terrain slope if below SNR,
     * but this requried knowledge of orbits, pixel,line coordinate
     * *
     * Input:
     * - MASTER: block of master, that will be filtered
     * - SLAVE:  block of slave, that will be filtered
     * Output:
     * - MASTER (SLAVE): filtered from indeces[0:numl-1]
     * (nlMean-1)/2 to numlines-(nlMean-1)/2-1
     */
    public static void filterBlock(ComplexDoubleMatrix masterDataBlock, // updated
                            ComplexDoubleMatrix slaveDataBlock,  // updated
                            int nlMean,
                            double SNRthreshold,
                            double RSR, // in MHz
                            double RBW, // in MHz
                            double alphaHamming,
                            int ovsFactor,
                            boolean doWeightCorrelFlag) throws Exception { // returned


        double meanSNR;
        double percentNotFiltered;

        /// define parameters ///

        final long numLines = masterDataBlock.rows;
        final long numPixs = masterDataBlock.columns;
        final long outputLines = numLines - nlMean + 1;
        final long firstLine = ((nlMean - 1) / 2);        // indices in matrix system
        final long lastLine = firstLine + outputLines - 1;
        final boolean doHammingFlag = (alphaHamming < 0.9999);
        // use oversampling before int. gen.
        final boolean doOversampleFlag = (ovsFactor != 1);
        int notFiltered = 0; // method counter

        /// sanity check on input paramaters ///
        if (!MathUtils.isOdd(nlMean)) {
            logger.error("nlMean has to be odd.");
            throw new IllegalArgumentException("nlMean has to be odd.");
        }
        if (!MathUtils.isPower2(numPixs)) {
            logger.error("numPixels (FFT) has to be power of 2.");
            throw new IllegalArgumentException("numPixels (FFT) has to be power of 2.");
        }
        if (!MathUtils.isPower2(ovsFactor)) {
            logger.error("oversample factor (FFT) has to be power of 2.");
            throw new IllegalArgumentException("oversample factor (FFT) has to be power of 2.");
        }
        if (slaveDataBlock.rows != numLines) {
            logger.error("slave not same size as master.");
            throw new IllegalArgumentException("slave not same size as master.");
        }
        if (slaveDataBlock.columns != numPixs) {
            logger.error("slave not same size as master.");
            throw new IllegalArgumentException("slave not same size as master.");
        }
        if (outputLines < 1) {
            logger.warn("no outputLines, continuing....");
        }

        /// local variables ///
        DoubleMatrix inverseHamming = null;

        /// shift parameters ////
        final double deltaF = RSR / numPixs;
//        final double freq = -RSR / 2.; // defined in defineFrequencyAxis

        DoubleMatrix freqAxis = defineFrequencyAxis(numPixs, RSR);

        if (doHammingFlag) {
            inverseHamming = WeightWindows.inverseHamming(freqAxis, RBW, RSR, alphaHamming);
        }

        //// COMPUTE CPLX IFG ON THE FLY -> power ////
        ComplexDoubleMatrix cplxIfg;
        if (doOversampleFlag) {
            cplxIfg = SarUtils.computeIfg(masterDataBlock, slaveDataBlock, 1, ovsFactor);
        } else {
            cplxIfg = SarUtils.computeIfg(masterDataBlock, slaveDataBlock);
        }

        long fftLength = cplxIfg.columns;

        logger.debug("is real4 accurate enough? it seems so!");

        SpectralUtils.fft_inplace(cplxIfg, 2);             // cplxIfg = fft over rows
        DoubleMatrix power = SarUtils.intensity(cplxIfg);  // power   = cplxIfg.*conj(cplxIfg);

        //// Use weighted correlation due to bias in normal definition
        // Note: Actually better de-weight with autoconvoluted hamming.
        if (doWeightCorrelFlag) {
            doWeightCorrel(RSR, RBW, numLines, numPixs, fftLength, power);
        }

        /// Average power to reduce noise : fft.ing in-place over data rows ///
        SpectralUtils.fft_inplace(masterDataBlock, 2);
        SpectralUtils.fft_inplace(slaveDataBlock, 2);
        logger.trace("Took FFT over rows of master, slave.");

        DoubleMatrix nlMeanPower = computeNlMeanPower(nlMean, fftLength, power);

        long shift; // returned by max
        meanSNR = 0.;
        double meanShift = 0.;

        // Start actual filtering
        for (long outLine = firstLine; outLine <= lastLine; ++outLine) {

            double totalPower = nlMeanPower.sum();
            double maxValue = nlMeanPower.max();
            shift = nlMeanPower.argmax();
            long lastShift = shift;
            double SNR = fftLength * (maxValue / (totalPower - maxValue));
            meanSNR += SNR;

            //// Check for negative shift
            boolean negShift = false;
            if (shift > (int) (fftLength / 2)) {
                shift = (int) fftLength - shift;
                lastShift = shift; // use this if current shift not OK.
                negShift = true;
            }

            // ______ Do actual filtering ______
            if (SNR < SNRthreshold) {
                notFiltered++; // update notFiltered counter
                shift = lastShift;
                logger.warn("using last shift for filter");
            }

            // interim variables
            meanShift += shift;
            DoubleMatrix filter;

            if (doHammingFlag) {
                // Newhamming is scaled and centered around new mean
                // filter is fftshifted
                filter = WeightWindows.hamming(
                        freqAxis.sub(0.5 * shift * deltaF),
                        RBW - (shift * deltaF),
                        RSR, alphaHamming);
                filter.muli(inverseHamming);
            } else {
                // no weighting of spectra
                // filter is fftshifted
                filter = WeightWindows.rect((freqAxis.sub(.5 * shift * deltaF)).div((RBW - shift * deltaF)));
            }

            //// Use freq. as returned by fft ////
            // Note that filter_slave = fliplr(filter_m)
            // and that this is also valid after ifftshift
            SpectralUtils.ifftshift_inplace(filter);

            //// Actual spectral filtering ////
            if (!negShift) {
                masterDataBlock.putRow((int) outLine, dotmult(masterDataBlock.getRow((int) outLine), new ComplexDoubleMatrix(filter)));
                fliplr_inplace(filter);
                slaveDataBlock.putRow((int) outLine, dotmult(slaveDataBlock.getRow((int) outLine), new ComplexDoubleMatrix(filter)));
            } else {
                slaveDataBlock.putRow((int) outLine, dotmult(slaveDataBlock.getRow((int) outLine), new ComplexDoubleMatrix(filter)));
                fliplr_inplace(filter);
                masterDataBlock.putRow((int) outLine, dotmult(masterDataBlock.getRow((int) outLine), new ComplexDoubleMatrix(filter)));
            }

            /// Update 'walking' mean
            if (outLine != lastLine) {
                DoubleMatrix line1 = power.getRow((int) (outLine - firstLine));
                DoubleMatrix lineN = power.getRow((int) (outLine - firstLine + nlMean));
                nlMeanPower.addi(lineN.sub(line1));
            }

        } // loop over outLines

        // IFFT of spectrally filtered data, and return these
        SpectralUtils.invfft_inplace(masterDataBlock, 2);
        SpectralUtils.invfft_inplace(slaveDataBlock, 2);

        // return these main filter call
        meanShift /= (outputLines - notFiltered);
        meanSNR /= outputLines;
        percentNotFiltered = 100. * (float) (notFiltered) / (float) outputLines;

        // Some info for this data block
        final double meanFrFreq = meanShift * deltaF;    // Hz?
        logger.debug("mean SHIFT for block"
                + ": " + meanShift
                + " = " + meanFrFreq / 1e6 + " MHz (fringe freq.).");

        logger.debug("mean SNR for block: " + meanSNR);
        logger.debug("filtered for block"
                + ": " + (100.00 - percentNotFiltered) + "%");

        if (percentNotFiltered > 60.0) {
            logger.warn("more then 60% of signal filtered?!?");
        }

    }

    //// HELPER PRIVATE METHODS ////

    private static DoubleMatrix computeNlMeanPower(final long nlMean, final long fftLength, DoubleMatrix power) {
//        DoubleMatrix nlmeanpower = sum(power(0,nlMean-1, 0,fftlength-1),1);
//        final IntervalRange rangeRows = new IntervalRange(0, (int) (nlMean));
//        final IntervalRange rangeColumns = new IntervalRange(0, (int) (fftLength));
        final Window window = new Window(0, nlMean-1, 0, fftLength-1);
        DoubleMatrix temp = new DoubleMatrix((int)nlMean, (int)fftLength);
        setdata(temp, window, power, window);
        return temp.columnSums();
    }


    // TODO: refactor to use arrays instead of loops
    private static void doWeightCorrel(final double RSR, final double RBW, final long numLines, final long numPixels, final long fftLength, DoubleMatrix data) {

        int j;
        int i;

        // weigth = numpoints in spectral convolution for fft squared for power...
        int indexNoPeak = (int) ((1. - (RBW / RSR)) * (float) (numPixels));
        for (j = 0; j < fftLength; ++j) {

            long nPnts = Math.abs(numPixels - j);
            double weight = (nPnts < indexNoPeak) ? Math.pow(numPixels, 2) : Math.pow(nPnts, 2); // ==zero

            for (i = 0; i < numLines; ++i) {
                data.put(i, j, data.get(i, j) / weight);
            }
        }
    }


    private static DoubleMatrix defineFrequencyAxis(final long numPixs, final double RSR) {
        final double deltaF = RSR / numPixs;
        final double freq = -RSR / 2.;
        DoubleMatrix freqAxis = new DoubleMatrix(1, (int) numPixs);
        for (int i = 0; i < numPixs; ++i) {
            freqAxis.put(0, i, freq + (i * deltaF));
        }
        return freqAxis;
    }

/*
    private static DoubleMatrix doHamming(float RSR, float RBW, float alphaHamming, long numPixs, DoubleMatrix freqAxis) throws Exception {
        DoubleMatrix inverseHamming = WeightWindows.hamming(freqAxis, RBW, RSR, alphaHamming);
        for (int i = 0; i < numPixs; ++i)
            if (inverseHamming.get(0, i) != 0.)
                inverseHamming.put(0, i, 1. / inverseHamming.get(0, i));
        return inverseHamming;
    }
*/

    // TODO: refactor InputEllips to "Ellipsoid" class of "org.esa.beam.framework.dataop.maptransf.Ellipsoid" and use GeoUtils of NEST;
    @Deprecated
    public static void rangefilterorbits(final todo_classes.inputgeneral generalinput,
                                         final todo_classes.input_filtrange inputfiltrange,
                                         final Ellipsoid ellips,
                                         final SLCImage master,
                                         final SLCImage slave,
                                         Orbit masterorbit,
                                         Orbit slaveorbit) {
    }

}
