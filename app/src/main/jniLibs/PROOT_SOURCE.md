# proot binaries

Built by .github/workflows/build-proot.yml (run 44834814737, commit 8499998e7f9fa595f27715d5342c74b46ab87e90). Do not edit by hand.

- Build scripts: https://github.com/green-green-avk/build-proot-android at 01f83b8841358450c78333d1b33ab30d4943bec4 (MIT)
- proot 0.15_release from https://github.com/green-green-avk/proot (GPL-2.0-or-later)
- talloc 2.1.14 from https://download.samba.org/pub/talloc/ (LGPL-3.0-or-later), linked statically into proot
- NDK 23.2.8568313

Source tarballs (sha256):

    proot https://github.com/green-green-avk/proot/archive/v0.15_release.tar.gz d678603aa80aea84969d2897d2a4f98acc8f6008defca960e40ce4ad4e8e893e
    talloc https://download.samba.org/pub/talloc/talloc-2.1.14.tar.gz b185602756a628bac507fa8af8b9df92ace69d27c0add5dab93190ad7c3367ce

Binaries (sha256):

    de0ace1adc76ab0555b3dbbfbff0e78c1ac14017b255262707867aea70c49437  ./arm64-v8a/libproot-loader.so
    1cf92eb379447ca19e13848be1e6dd57832c7d241a6fb8e2bad06df4523721ca  ./arm64-v8a/libproot-loader32.so
    8257b2e7a57690e5b7fd188452aca6ab27bdff1c6add8e0c4ad090b50fa7f48b  ./arm64-v8a/libproot-userland.so
    cd47a4bac6026f524233ff524c5d905fede6f317d7e9bfaae3f26be6971df5da  ./arm64-v8a/libproot.so
    0f08d951cbcbb7065928a0ee89a1b6be89f306bc37fb589bc304f58fb696cc27  ./armeabi-v7a/libproot-loader.so
    bcc8668a885f3067bfe563531f49a550bcf3d1e912e958290c90e8524ed02c29  ./armeabi-v7a/libproot-userland.so
    4e750ffc8bb0186d78058197688558ea5d072fd0e6da4fbc00cdaaef88530c27  ./armeabi-v7a/libproot.so
    c1f8d7afe1f72951c8afee2650fce3b80f5b6daa98a6f228ed1a2e8a70775bd3  ./x86_64/libproot-loader.so
    957d8484a5d491823b8cec24701cb64377672000e800bfc400971744a50d0919  ./x86_64/libproot-loader32.so
    4c789140cd546ff02df84b6b88aed7537fd370978188fae1d6264558c1dd1110  ./x86_64/libproot-userland.so
    4f55f839a3d5f66209e03704fbb2c1a63798e9bb8486738353e23e690834ad38  ./x86_64/libproot.so
