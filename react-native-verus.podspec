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
    echo "Preparing react-native-verus sources..."
    set -e  # abort on first failure

    cd "$(pwd)"
    echo "Running yarn install in react-native-verus..."
    yarn install --frozen-lockfile --ignore-scripts

    echo "Running update-sources..."
    npx sucrase-node ./scripts/updateSources.ts
    #npm run update-sources

    echo "Done preparing react-native-verus."
  CMD

  s.source_files =
    "ios/react-native-verus-Bridging-Header.h",
    "ios/RNZcash.m",
    "ios/RNZcash.swift",
    "ios/zcashlc.h",
    "ios/ZCashLightClientKit/**/*.swift"
  s.resource_bundles = {
    "zcash-mainnet" => "ios/ZCashLightClientKit/Resources/checkpoints/mainnet/*.json",
    "zcash-testnet" => "ios/ZCashLightClientKit/Resources/checkpoints/testnet/*.json"
  }

  s.vendored_frameworks = "ios/ZcashLightClientKit/**/*.framework"
  s.preserve_paths = "ios/ZCashLightClientKit"

  s.dependency "React-Core"
  s.dependency "MnemonicSwift", "~> 2.0"
  s.dependency "gRPC-Swift", "~> 1.8"
  s.dependency "SQLite.swift", "~> 0.12"
  s.dependency "React-Core"
end
