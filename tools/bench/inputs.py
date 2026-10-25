"""Every downloaded input of the benchmark and the release check, pinned by URL and SHA-256."""

SERVER = 'server-26.3.jar'
FABRIC_LAUNCHER = 'fabric-server-launch.jar'  # loader 0.20.1, installer 1.1.2
NEOFORGE_INSTALLER = 'neoforge-26.3.0.95-installer.jar'
FABRIC_API = 'fabric-api-0.165.0+26.3.jar'
LITHIUM = 'lithium-fabric-0.26.2+mc26.3.jar'
C2ME = 'c2me-fabric-mc26.3-0.4.2-alpha.0.106.jar'
PAPER = 'paper-26.3-218.jar'
FOLIA = 'folia-26.3-5.jar'
SCALABLELUX = 'ScalableLux-fabric-mc26.3-0.3.0-alpha.0.6-all.jar'

DOWNLOADS = {
    SERVER: ('https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar',
             'd052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64'),
    FABRIC_LAUNCHER: ('https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar',
                      '70446947655b4f8f286aba896aa4c19a255e8ca8a730636ef2a6017ae4415d95'),
    NEOFORGE_INSTALLER: ('https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.95/'
                         'neoforge-26.3.0.95-installer.jar',
                         '59ed4c58cc5b49e3df876658196d34c23184f5f73986e352508cdbddc88aa7fb'),
    FABRIC_API: ('https://cdn.modrinth.com/data/P7dR8mSH/versions/EQKgpeKu/fabric-api-0.165.0%2B26.3.jar',
                 '18442e2ad466d6ef41c37dbb4cb68495e6ca6658b1f1ee9be76143d155ef2c88'),
    LITHIUM: ('https://cdn.modrinth.com/data/gvQqBUqZ/versions/xS0Q8LSi/lithium-fabric-0.26.2%2Bmc26.3.jar',
              '0be5b17ef9f6e9244cd07b24b227553a73aec0cf93158cf28099f3094c167f1b'),
    C2ME: ('https://cdn.modrinth.com/data/VSNURh3q/versions/gKKkuSYc/c2me-fabric-mc26.3-0.4.2-alpha.0.106.jar',
           '41b49a6276d477ca45b4398457a658e72063dab2251e20fd73faa89279efc288'),
    PAPER: ('https://fill-data.papermc.io/v1/objects/'
            'cdd56d33f27e77110e6f8b43cf6d9571c3854c9ae099986b24a3c18c5904cc11/paper-26.3-218.jar',
            'cdd56d33f27e77110e6f8b43cf6d9571c3854c9ae099986b24a3c18c5904cc11'),
    FOLIA: ('https://fill-data.papermc.io/v1/objects/'
            'c105f6be9463b2da37568b620e81b9ab2f6a6ae771f8fff9caf7a1c0946b340a/folia-26.3-5.jar',
            'c105f6be9463b2da37568b620e81b9ab2f6a6ae771f8fff9caf7a1c0946b340a'),
    SCALABLELUX: ('https://cdn.modrinth.com/data/Ps1zyz6x/versions/g4eqNSKd/'
                  'ScalableLux-fabric-mc26.3-0.3.0-alpha.0.6-all.jar',
                  'e9137a04fdefca4fd1f4740ab3dc6194b5ea7c15456c6691376b910053853a29'),
}
