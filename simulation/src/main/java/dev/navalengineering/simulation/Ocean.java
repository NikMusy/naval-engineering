package dev.navalengineering.simulation;

/** Deterministic Gerstner surface in Minecraft world coordinates, metres and seconds. */
public final class Ocean {
    public static final double GRAVITY = 9.81;
    public static final double WATER_DENSITY = 1025;
    private static final double TAU = 2 * Math.PI;
    private static final Wave[] WAVES = {
            new Wave(0.33, 25, 22, .8),
            new Wave(0.25, 15, -19, 2.1),
            new Wave(0.19, 10, 72, 4.8),
            new Wave(0.14, 6, -62, 1.4),
            new Wave(0.09, 3.8, 37, 3.5)
    };

    private Ocean() {}

    public record Frame(double seconds, double weather) {
        public Frame {
            weather = Math.clamp(weather, 0, 1);
        }

        public double windSpeed() {
            return 3 + 22 * weather;
        }

        public double amplitude() {
            return .075 + .66 * Math.pow((windSpeed() - 3) / 22, 1.5);
        }

        public double tide() {
            return .11 * Math.sin(TAU * seconds / 900);
        }

        public double elevation(double x, double z, double datum, double exposure) {
            return evaluate(x, z, datum, exposure, null);
        }

        public void sample(double x, double z, double datum, double exposure, Water out) {
            evaluate(x, z, datum, exposure, out);
        }

        private double evaluate(double x, double z, double datum, double exposure, Water out) {
            // Leave the animated mesh above exposed vanilla source water, including at troughs.
            double amplitude = amplitude() * exposure;
            double elevation = datum + exposure * (amplitude() + .14 + tide());
            if (exposure == 0) {
                if (out != null) out.set(elevation, 0, 0, 0);
                return elevation;
            }

            double parameterX = x;
            double parameterZ = z;
            for (int iteration = 0; iteration < 3; iteration++) {
                double offsetX = 0;
                double offsetZ = 0;
                for (Wave wave : WAVES) {
                    double phase = wave.phase(parameterX, parameterZ, seconds);
                    double offset = .58 * amplitude * wave.weight * Math.cos(phase);
                    offsetX += wave.x * offset;
                    offsetZ += wave.z * offset;
                }
                parameterX = x - offsetX;
                parameterZ = z - offsetZ;
            }

            double velocityX = exposure * (.22 + .28 * weather) * Math.cos(.004 * z);
            double velocityZ = exposure * (.14 + .20 * weather) * Math.sin(.004 * x + .6);
            double velocityY = 0;
            for (Wave wave : WAVES) {
                double phase = wave.phase(parameterX, parameterZ, seconds);
                double localAmplitude = amplitude * wave.weight;
                double crest = localAmplitude * Math.sin(phase);
                elevation += crest;
                if (out != null) {
                    velocityX += .58 * crest * wave.frequency * wave.x;
                    velocityZ += .58 * crest * wave.frequency * wave.z;
                    velocityY -= localAmplitude * wave.frequency * Math.cos(phase);
                }
            }
            if (out != null) out.set(elevation, velocityX, velocityY, velocityZ);
            return elevation;
        }
    }

    private static final class Wave {
        final double weight;
        final double x;
        final double z;
        final double waveNumber;
        final double frequency;
        final double phase;

        Wave(double weight, double length, double heading, double phase) {
            this.weight = weight;
            x = Math.cos(Math.toRadians(heading));
            z = Math.sin(Math.toRadians(heading));
            waveNumber = TAU / length;
            frequency = Math.sqrt(GRAVITY * waveNumber);
            this.phase = phase;
        }

        double phase(double x, double z, double seconds) {
            return waveNumber * (this.x * x + this.z * z) - frequency * seconds + phase;
        }
    }

    @FunctionalInterface
    public interface Surface {
        void sample(double x, double y, double z, Water result);
    }

    public static final class Water {
        public double height = Double.NEGATIVE_INFINITY;
        public double vx;
        public double vy;
        public double vz;

        public void set(double height, double vx, double vy, double vz) {
            this.height = height;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
        }
    }
}

