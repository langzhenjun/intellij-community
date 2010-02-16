package com.jetbrains.python;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.annotations.NonNls;

import java.io.File;
import java.util.List;

/**
 * @author yole
 */
public class PythonMockSdk {
  @NonNls private static final String MOCK_SDK_NAME = "Mock Jython SDK";

  public static Sdk findOrCreate() {
    final List<Sdk> sdkList = ProjectJdkTable.getInstance().getSdksOfType(PythonSdkType.getInstance());
    for (Sdk sdk : sdkList) {
      if (sdk.getName().equals(MOCK_SDK_NAME)) {
        return sdk;
      }
    }
    return create();
  }

  public static Sdk create() {
    String sdkHome = new File(PathManager.getHomePath(), "python/lib/mock_interpreter").getPath();
    SdkType sdkType = PythonSdkType.getInstance();

    final Sdk sdk = new ProjectJdkImpl(MOCK_SDK_NAME, sdkType) {
      @Override
      public String getVersionString() {
        return "Jython 2.5 Mock SDK"; // TODO: implement language level here
      }
    };
    final SdkModificator sdkModificator = sdk.getSdkModificator();
    sdkModificator.setHomePath(sdkHome);
    PythonSdkType.setupSdkPaths(sdkModificator, null);
    sdkModificator.commitChanges();
    return sdk;
  }
}
