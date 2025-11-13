require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = package['name']
  s.version      = package['version']
  s.summary      = package['description']
  s.homepage     = package['homepage']
  s.license      = package['license']
  s.authors      = package['author']

  s.platform     = :ios, "13.0"
  s.source = {
    :git => "https://github.com/who-biz/react-native-zcash.git",
    :tag => "v#{s.version}"
  }

  s.prepare_command = <<-CMD
    echo "[react-native-verus] prepare_command starting..."
    set -e

    echo "PWD=$(pwd)"
    export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"
    export HUSKY=0
    export CI=1

    echo "Installing deps (ignoring lifecycle scripts to avoid Husky)..."
    yarn install --frozen-lockfile --ignore-scripts || npm ci --ignore-scripts

    echo "Transpiling TS -> JS (with imports->CJS) ..."
    rm -rf ./scripts-built
      ./node_modules/.bin/sucrase ./scripts \
      --transforms typescript,imports \
      --out-dir ./scripts-built

    if [ ! -f "./scripts-built/updateSources.js" ]; then
      echo "ERROR: Expected ./scripts-built/updateSources.js after transpile."
      exit 1
    fi

    echo "Running updateSources.js ..."
    node ./scripts-built/updateSources.js

    echo "[react-native-verus] prepare_command finished."
  CMD

  s.source_files =
    "ios/react-native-verus-Bridging-Header.h",
    "ios/VerusLightClient.m",
    "ios/VerusLightClient.swift",
    "ios/zcashlc.h",
    "ios/ZCashLightClientKit/**/*.swift"
  s.resource_bundles = {
    "zcash-mainnet" => "ios/ZCashLightClientKit/Resources/checkpoints/mainnet/*.json",
    "zcash-testnet" => "ios/ZCashLightClientKit/Resources/checkpoints/testnet/*.json"
  }

  s.vendored_frameworks = "ios/libzcashlc.xcframework"
  s.libraries = "z", "sqlite3", "c++"
  s.preserve_paths = "ios/libzcashlc.xcframework"

  s.user_target_xcconfig = {
    'LIBRARY_SEARCH_PATHS'   => '$(inherited) $(PODS_CONFIGURATION_BUILD_DIR) $(PODS_XCFRAMEWORKS_BUILD_DIR)/libzcashlc',
    'FRAMEWORK_SEARCH_PATHS' => '$(inherited) $(PODS_CONFIGURATION_BUILD_DIR) $(PODS_XCFRAMEWORKS_BUILD_DIR)'
  }

  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS'    => '$(inherited) $(PODS_TARGET_SRCROOT)/ios $(PODS_XCFRAMEWORKS_BUILD_DIR)/libzcashlc/**',
    'LIBRARY_SEARCH_PATHS'   => '$(inherited) $(PODS_CONFIGURATION_BUILD_DIR) $(PODS_XCFRAMEWORKS_BUILD_DIR)/libzcashlc',
    'DEAD_CODE_STRIPPING'    => 'NO',
  }

  s.dependency "React-Core"
  s.dependency "MnemonicSwift", "~> 2.0"
  s.dependency "gRPC-Swift", "~> 1.8"
  s.dependency "SQLite.swift", "~> 0.12"
  s.dependency "React-Core"
end
