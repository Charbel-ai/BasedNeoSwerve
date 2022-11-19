package frc.robot.subsystems;

import java.util.List;
import java.util.function.DoubleSupplier;

import com.ctre.phoenix.sensors.Pigeon2;

import edu.wpi.first.math.Nat;
import edu.wpi.first.math.Vector;
import edu.wpi.first.math.estimator.SwerveDrivePoseEstimator;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.numbers.N7;
import edu.wpi.first.util.sendable.SendableBuilder;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.Command;
import static edu.wpi.first.wpilibj2.command.Commands.*;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants;
import frc.robot.RobotContainer;
import frc.robot.utils.SwerveModule;
import frc.robot.utils.VisionMeasurement;

public class Swerve extends SubsystemBase {
  private final RobotContainer container;

  private final SwerveModule[] modules;

  private final SwerveDrivePoseEstimator<N7, N7, N7> swerveOdometry;

  private final Pigeon2 gyro;

  public Swerve(RobotContainer container) {
    this.container = container;

    gyro = new Pigeon2(Constants.kSwerve.PIGEON2_ID);
    gyro.configFactoryDefault();
    zeroGyro();

    modules = new SwerveModule[] {
      new SwerveModule(0, Constants.kSwerve.MOD_0_Constants),
      new SwerveModule(1, Constants.kSwerve.MOD_1_Constants),
      new SwerveModule(2, Constants.kSwerve.MOD_2_Constants),
      new SwerveModule(3, Constants.kSwerve.MOD_3_Constants),
    };

    swerveOdometry = new SwerveDrivePoseEstimator<>(
      Nat.N7(),
      Nat.N7(),
      Nat.N7(), 
      getYaw(),
      new Pose2d(),
      getPositions(),
      Constants.kSwerve.KINEMATICS,
      Constants.kSwerve.STATE_STANDARD_DEVIATION,
      Constants.kSwerve.LOCAL_MEASUREMENTS_STANDARD_DEVIATION,
      new Vector<>(Nat.N3()));
  }

  /** 
   * This is called a command factory method, and these methods help reduce the
   * number of files in the command folder, increasing readability and reducing
   * boilerplate. 
   * 
   * Double suppliers are just any function that returns a double.
   */
  public Command drive(DoubleSupplier xTranslationAxis, DoubleSupplier yTranslationAxis, DoubleSupplier rotationAxis, boolean isFieldRelative, boolean isOpenLoop) {
    return run(() -> {
      // Grabbing input from suppliers.
      double xTranslation = xTranslationAxis.getAsDouble();
      double yTranslation = yTranslationAxis.getAsDouble();
      double rotation = rotationAxis.getAsDouble();

      // Adding deadzone.
      xTranslation = Math.abs(xTranslation) < Constants.kControls.AXIS_DEADZONE ? 0 : xTranslation;
      yTranslation = Math.abs(yTranslation) < Constants.kControls.AXIS_DEADZONE ? 0 : yTranslation;
      rotation = Math.abs(rotation) < Constants.kControls.AXIS_DEADZONE ? 0 : rotation;

      // Get desired module states.
      ChassisSpeeds chassisSpeeds = isFieldRelative
        ? ChassisSpeeds.fromFieldRelativeSpeeds(xTranslation, yTranslation, rotation, getYaw())
        : new ChassisSpeeds(xTranslation, yTranslation, rotation);

      SwerveModuleState[] states = Constants.kSwerve.KINEMATICS.toSwerveModuleStates(chassisSpeeds);

      setModuleStates(states, isOpenLoop);
    }, this).withName("SwerveDriveCommand");
  }

  /** To be used by auto. Use the drive method during teleop. */
  public void setModuleStates(SwerveModuleState[] states, boolean isOpenLoop) {
    // Makes sure the module states don't exceed the max speed.
    SwerveDriveKinematics.desaturateWheelSpeeds(states, Constants.kSwerve.MAX_VELOCITY_METERS_PER_SECOND);

    for (int i = 0; i < states.length; i++) {
      modules[i].setState(states[i], isOpenLoop);
    }
  }

  public SwerveModuleState[] getStates() {
    SwerveModuleState currentStates[] = new SwerveModuleState[modules.length];
    for (int i = 0; i < modules.length; i++) {
      currentStates[i] = modules[i].getState();
    }

    return currentStates;
  }

  public SwerveModulePosition[] getPositions() {
    SwerveModulePosition currentStates[] = new SwerveModulePosition[modules.length];
    for (int i = 0; i < modules.length; i++) {
      currentStates[i] = modules[i].getPosition();
    }

    return currentStates;
  }

  public Rotation2d getYaw() {
    return Rotation2d.fromDegrees(gyro.getYaw());
  }

  public Command zeroGyroCommand() {
    return runOnce(this::zeroGyro).withName("ZeroGyroCommand");
  }

  private void zeroGyro() {
    gyro.setYaw(0);
  }

  public Pose2d getPose() {
    return swerveOdometry.getEstimatedPosition();
  }

  public void resetOdometry(Pose2d pose) {
    swerveOdometry.resetPosition(pose, getYaw());
  }

  @Override
  public void periodic() {
    swerveOdometry.update(getYaw(), getStates(), getPositions());

    List<VisionMeasurement> measurements = container.vision.getMeasurements();

    for (VisionMeasurement measurement : measurements) {
      swerveOdometry.addVisionMeasurement(
        measurement.pose,
        Timer.getFPGATimestamp() - (measurement.latencyMillis / 1000),
        Constants.kSwerve.VISION_STANDARD_DEVIATION.times(measurement.ambiguity)); 
    }
  }

  @Override
  public void initSendable(SendableBuilder builder) {
    super.initSendable(builder);
    for (SwerveModule module : modules) {
      builder.addStringProperty(
        String.format("Module %d",
        module.moduleNumber),
        () -> {
          SwerveModuleState state = module.getState();
          return String.format("%.2fm/s %.0frad", state.speedMetersPerSecond, state.angle.getRadians());
        },
        null);
    }
  }
}
