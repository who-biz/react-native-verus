// Run this script as `node -r sucrase/register ./scripts/updateSources.ts`
//
// It will download third-party source code, modify it,
// and install it into the correct locations.

import { execSync } from 'child_process'
import { deepList, justFiles, makeNodeDisklet, navigateDisklet } from 'disklet'
import { existsSync, mkdirSync } from 'fs'
import { join, basename, dirname } from 'path'

import { copyCheckpoints } from './copyCheckpoints'

const disklet = makeNodeDisklet(join(__dirname, '../'))
const tmp = join(__dirname, '../tmp')

async function main(): Promise<void> {
  if (!existsSync(tmp)) mkdirSync(tmp)
  await downloadSources()
  await rebuildXcframework()
  await copySwift()
  await copyCheckpoints(disklet)
}

function downloadSources(): void {
  getRepo(
    'ZcashLightClientKit',
    'https://github.com/who-biz/verus-swift-wallet-sdk.git',
    // 2.0.3:
    'master'
  )
  getRepo(
    'zcash-light-client-ffi',
    'https://github.com/who-biz/verus-lightclient-ffi.git',
    // 0.4.0:
    'master'
  )
}

async function rebuildXcframework(): Promise<void> {
  console.log("Creating XCFramework…");

  // Always start from a clean XCFramework
  await disklet.delete("ios/libzcashlc.xcframework");

  const vendorRoot = join(__dirname, "..", "tmp", "zcash-light-client-ffi");

  quietExec([
    "bash",
    "-lc",
    `
      set -euo pipefail
      cd "${vendorRoot}"
      make clean
      make install
      make xcframework
    `,
  ]);

  await disklet.setData(
    "tmp/lib/ios-simulator/libzcashlc.a",
    await disklet.getData(
      "tmp/zcash-light-client-ffi/products/ios-simulator/universal/libzcashlc.a"
    )
  );
  await disklet.setData(
    "tmp/lib/ios/libzcashlc.a",
    await disklet.getData(
      "tmp/zcash-light-client-ffi/products/ios-device/universal/libzcashlc.a"
    )
  );

  await disklet.setText(
    "ios/ZCashLightClientKit/Rust/zcashlc.h",
    await disklet.getText(
      "tmp/zcash-light-client-ffi/rust/target/Headers/zcashlc.h"
    )
  );

  quietExec([
    "xcodebuild",
    "-create-xcframework",
    "-library",
    join(__dirname, "../tmp/lib/ios-simulator/libzcashlc.a"),
    "-library",
    join(__dirname, "../tmp/lib/ios/libzcashlc.a"),
    "-output",
    join(__dirname, "../ios/libzcashlc.xcframework"),
  ]);

  console.log("XCFramework created at ios/libzcashlc.xcframework");
}

/**
 * Copies swift code, with modifications.
 */
async function copySwift(): Promise<void> {
  console.log('Copying swift sources...')
  const fromDisklet = navigateDisklet(
    disklet,
    'tmp/ZCashLightClientKit/Sources'
  )
  const toDisklet = navigateDisklet(disklet, 'ios')
  await toDisklet.delete('ZCashLightClientKit/')
  const files = justFiles(await deepList(fromDisklet, 'ZCashLightClientKit/'))

  for (const file of files) {
    const text = await fromDisklet.getText(file)
    const fixed = text
      // We are lumping everything into one module,
      // so we don't need to import this externally:
      .replace('import libzcashlc', '')
      // The Swift package manager synthesizes a "Bundle.module" accessor,
      // but with CocoaPods we need to load things manually:
      .replace(
        'Bundle.module.bundleURL.appendingPathComponent("checkpoints/mainnet/")',
        'Bundle.main.url(forResource: "zcash-mainnet", withExtension: "bundle")!'
      )
      .replace(
        'Bundle.module.bundleURL.appendingPathComponent("checkpoints/testnet/")',
        'Bundle.main.url(forResource: "zcash-testnet", withExtension: "bundle")!'
      )
      // This block of code uses "Bundle.module" too,
      // but we can just delete it since phone builds don't need it:
      .replace(/static let macOS = BundleCheckpointURLProvider.*}\)/s, '')
      .replace(
        `public static func from(decimal: Decimal) -> Zatoshi`,
        `public static func from(decimal: Foundation.Decimal) -> Zatoshi`
      )

    await toDisklet.setText(file, fixed)
  }

  // Copy the Rust header into the Swift location:
  await disklet.setText(
    'ios/zcashlc.h',
    await disklet.getText(
      'tmp/zcash-light-client-ffi/releases/XCFramework/libzcashlc.xcframework/ios-arm64/libzcashlc.framework/Headers/zcashlc.h'
    )
  )
}

/**
 * Clones a git repo and checks our a hash.
 */
function getRepo(name: string, uri: string, hash: string): void {
  const path = join(tmp, name)

  // Either clone or fetch:
  if (!existsSync(path)) {
    console.log(`Cloning ${name}...`)
    loudExec(tmp, ['git', 'clone', uri, name])
  } else {
    // We might already have the right commit, so fetch lazily:
    try {
      loudExec(path, ['git', 'fetch'])
    } catch (error) {
      console.log(error)
    }
  }

  // Checkout:
  console.log(`Checking out ${name}...`)
  execSync(`git checkout -f ${hash}`, {
    cwd: path,
    stdio: 'inherit',
    encoding: 'utf8'
  })
}

/**
 * Runs a command and returns its results.
 */
function quietExec(argv: string[]): string {
  return execSync(argv.join(' '), {
    cwd: tmp,
    encoding: 'utf8'
  }).replace(/\n$/, '')
}

/**
 * Runs a command and displays its results.
 */
function loudExec(path: string, argv: string[]): void {
  execSync(argv.join(' '), {
    cwd: path,
    stdio: 'inherit',
    encoding: 'utf8'
  })
}

main().catch(error => {
  console.log(error)
  process.exit(1)
})

