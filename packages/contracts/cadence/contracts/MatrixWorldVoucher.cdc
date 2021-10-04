import NonFungibleToken from "./lib/NonFungibleToken.cdc"

pub contract MatrixWorldVoucher: NonFungibleToken {
    pub event ContractInitialized()
    pub event Withdraw(id: UInt64, from: Address?)
    pub event Deposit(id: UInt64, to: Address?)
    pub event Minted(id: UInt64, name: String, description:String, animationUrl:String, hash: String, type: String)

    pub let CollectionStoragePath: StoragePath
    pub let CollectionPublicPath: PublicPath
    pub let MinterStoragePath: StoragePath

    pub var totalSupply: UInt64
    priv var nftHashes: {String: Bool}

    pub resource interface NFTPublic {
        pub let id: UInt64
        pub let metadata: Metadata
    }

    pub struct Metadata {
        pub let name: String
        pub let description: String
        pub let animationUrl: String
        pub let hash: String
        pub let type: String

        init(name: String, description: String, animationUrl: String, hash: String, type: String) {
            self.name = name
            self.description = description
            self.animationUrl = animationUrl
            self.hash = hash
            self.type = type
        }
    }

   pub resource NFT: NonFungibleToken.INFT, NFTPublic {
        pub let id: UInt64
        pub let metadata: Metadata
        init(initID: UInt64,metadata: Metadata) {
            self.id = initID
            self.metadata = metadata
        }
    }

    pub resource interface MatrixWorldVoucherCollectionPublic {
        pub fun deposit(token: @NonFungibleToken.NFT)
        pub fun getIDs(): [UInt64]
        pub fun borrowNFT(id: UInt64): &NonFungibleToken.NFT
        pub fun borrowVoucher(id: UInt64): &MatrixWorldVoucher.NFT? {
            post {
                (result == nil) || (result?.id == id):
                    "Cannot borrow MatrixWorldVoucher reference: The ID of the returned reference is incorrect"
            }
        }
    }

    pub resource Collection: MatrixWorldVoucherCollectionPublic, NonFungibleToken.Provider, NonFungibleToken.Receiver, NonFungibleToken.CollectionPublic {
        pub var ownedNFTs: @{UInt64: NonFungibleToken.NFT}

        pub fun withdraw(withdrawID: UInt64): @NonFungibleToken.NFT {
            let token <- self.ownedNFTs.remove(key: withdrawID) ?? panic("missing NFT")

            emit Withdraw(id: token.id, from: self.owner?.address)

            return <-token
        }

        pub fun deposit(token: @NonFungibleToken.NFT) {
            let token <- token as! @MatrixWorldVoucher.NFT

            let id: UInt64 = token.id

            let oldToken <- self.ownedNFTs[id] <- token

            emit Deposit(id: id, to: self.owner?.address)

            destroy oldToken
        }


        pub fun getIDs(): [UInt64] {
            return self.ownedNFTs.keys
        }

        pub fun borrowNFT(id: UInt64): &NonFungibleToken.NFT {
            return &self.ownedNFTs[id] as &NonFungibleToken.NFT
        }

        pub fun borrowVoucher(id: UInt64): &MatrixWorldVoucher.NFT? {
            if self.ownedNFTs[id] != nil {
                let ref = &self.ownedNFTs[id] as auth &NonFungibleToken.NFT
                return ref as! &MatrixWorldVoucher.NFT
            } else {
                return nil
            }
        }

        destroy() {
            destroy self.ownedNFTs
        }

        init () {
            self.ownedNFTs <- {}
        }
    }

    pub fun createEmptyCollection(): @NonFungibleToken.Collection {
        return <- create Collection()
    }

    pub struct NftData {
        pub let metadata: MatrixWorldVoucher.Metadata
        pub let id: UInt64
        init(metadata: MatrixWorldVoucher.Metadata, id: UInt64) {
            self.metadata= metadata
            self.id=id
        }
    }

    pub fun getNft(address:Address) : [NftData] {
        var nftData: [NftData] = []
        let account = getAccount(address)

        if let nftCollection = account.getCapability(self.CollectionPublicPath).borrow<&{MatrixWorldVoucher.MatrixWorldVoucherCollectionPublic}>()  {
            for id in nftCollection.getIDs() {
                var nft = nftCollection.borrowVoucher(id: id)
                nftData.append(NftData(metadata: nft!.metadata,id: id))
            }
        }
        return nftData
    }

	pub resource NFTMinter {
		pub fun mintNFT(
            recipient: &{NonFungibleToken.CollectionPublic},
            name: String,
            description: String,
            animationUrl: String
            hash: String,
            type: String) {
            emit Minted(id: MatrixWorldVoucher.totalSupply, name: name, description: description, animationUrl: animationUrl, hash: hash, type: type)
            assert(!MatrixWorldVoucher.nftHashes.containsKey(hash), message: "Duplicate voucher hash")
			recipient.deposit(token: <-create MatrixWorldVoucher.NFT(
			    initID: MatrixWorldVoucher.totalSupply,
			    metadata: Metadata(
                    name: name,
                    description:description,
                    animationUrl: animationUrl,
                    hash: hash,
                    type: type
                )))

            MatrixWorldVoucher.totalSupply = MatrixWorldVoucher.totalSupply + (1 as UInt64)
            MatrixWorldVoucher.nftHashes[hash] = true;
		}
	}

    init() {
        self.CollectionStoragePath = /storage/MatrixWorldVoucherCollection
        self.CollectionPublicPath = /public/MatrixWorldVoucherCollection
        self.MinterStoragePath = /storage/MatrixWorldVoucherMinter

        self.totalSupply = 0
        self.nftHashes = {}

        let minter <- create NFTMinter()
        self.account.save(<-minter, to: self.MinterStoragePath)

        emit ContractInitialized()
    }
}
