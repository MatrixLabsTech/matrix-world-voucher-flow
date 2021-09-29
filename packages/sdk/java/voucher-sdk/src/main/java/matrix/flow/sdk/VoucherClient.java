package matrix.flow.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.nftco.flow.sdk.Flow;
import com.nftco.flow.sdk.FlowAccessApi;
import com.nftco.flow.sdk.FlowAccount;
import com.nftco.flow.sdk.FlowAccountKey;
import com.nftco.flow.sdk.FlowAddress;
import com.nftco.flow.sdk.FlowArgument;
import com.nftco.flow.sdk.FlowEvent;
import com.nftco.flow.sdk.FlowId;
import com.nftco.flow.sdk.FlowPublicKey;
import com.nftco.flow.sdk.FlowScript;
import com.nftco.flow.sdk.FlowTransaction;
import com.nftco.flow.sdk.FlowTransactionProposalKey;
import com.nftco.flow.sdk.FlowTransactionResult;
import com.nftco.flow.sdk.FlowTransactionStatus;
import com.nftco.flow.sdk.HashAlgorithm;
import com.nftco.flow.sdk.SignatureAlgorithm;
import com.nftco.flow.sdk.Signer;
import com.nftco.flow.sdk.cadence.AddressField;
import com.nftco.flow.sdk.cadence.StringField;
import com.nftco.flow.sdk.cadence.UFix64NumberField;
import com.nftco.flow.sdk.crypto.Crypto;
import com.nftco.flow.sdk.crypto.PrivateKey;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.util.encoders.Hex;

public final class VoucherClient {

    private final FlowAccessApi accessAPI;
    private final String accountAddress;
    private final PrivateKey privateKey;
    private final String fusdAddress;
    private final String fungibleTokenAddress;

    static final int DAYS_IN_WEEK = 7;
    static final String FUNGIBLE_TOKEN_ADDRESS_TEMP = "%FUNGIBLE_TOKEN_ADDRESS";
    static final String FUSD_ADDRESS_TEMP = "%FUSD_ADDRESS";

    public VoucherClient(String host, int port, String privateKeyHex, String accountAddress, String fusdAddress,
            String fungibleTokenAddress) {
        this.accessAPI = Flow.newAccessApi(host, port);
        this.privateKey = Crypto.decodePrivateKey(privateKeyHex);
        this.accountAddress = accountAddress;
        this.fusdAddress = fusdAddress;
        this.fungibleTokenAddress = fungibleTokenAddress;
    }

    public FlowId transferFUSD(FlowAddress senderAddress, FlowAddress recipientAddress, BigDecimal amount)
            throws Exception {
        if (amount.scale() != 8) {
            throw new Exception("FUSD amount must have exactly 8 decimal places of precision (e.g. 10.00000000)");
        }
        FlowAccountKey senderAccountKey = this.getAccountKey(senderAddress, 0);
        String cadenceScript = readScript("transfer_fusd.cdc.temp");
        cadenceScript = cadenceScript.replaceAll(VoucherClient.FUNGIBLE_TOKEN_ADDRESS_TEMP, this.fungibleTokenAddress);
        cadenceScript = cadenceScript.replaceAll(VoucherClient.FUSD_ADDRESS_TEMP, this.fusdAddress);
        FlowTransaction tx = new FlowTransaction(new FlowScript(cadenceScript.getBytes()),
                Arrays.asList(new FlowArgument(new UFix64NumberField(amount.toPlainString())),
                        new FlowArgument(new AddressField(recipientAddress.getBase16Value()))),
                this.getLatestBlockID(), 100L,
                new FlowTransactionProposalKey(senderAddress, senderAccountKey.getId(),
                        senderAccountKey.getSequenceNumber()),
                senderAddress, Arrays.asList(senderAddress), new ArrayList<>(), new ArrayList<>());

        Signer signer = Crypto.getSigner(this.privateKey, senderAccountKey.getHashAlgo());
        tx = tx.addEnvelopeSignature(senderAddress, senderAccountKey.getId(), signer);

        FlowId txID = this.accessAPI.sendTransaction(tx);
        this.waitForSeal(txID);
        return txID;
    }

    public void VerifyFUSDTransaction(String payerAddress, BigDecimal amount, String transactionId) throws Exception {
        FlowTransactionResult txResult = this.waitForSeal((new FlowId(transactionId)));

        if (amount.scale() != 8) {
            throw new Exception("FUSD amount must have exactly 8 decimal places of precision (e.g. 10.00000000)");
        }

        List<FlowEvent> events = txResult.getEvents();

        if (events.size() != 2) {
            throw new Exception("This not an official FUSD transferTokens event");
        }

        FlowEvent firstEvent = events.get(0);
        FlowEvent secondEvent = events.get(1);

        if (!firstEvent.getType().toString().equals("A." + this.fusdAddress + ".FUSD.TokensWithdrawn")
                || !secondEvent.getType().toString().equals("A." + this.fusdAddress + ".FUSD.TokensDeposited")) {
            throw new Exception("This not an official FUSD transferTokens event");
        }
        UFix64NumberField amountFrom = (UFix64NumberField) firstEvent.getField("amount");

        if (!amountFrom.toBigDecimal().equals(amount)) {
            throw new Exception("Withdrawn FUSD amount not match");
        }
        AddressField from = (AddressField) firstEvent.getField("from").getValue();
        if (!from.getValue().toString().substring(2).equals(payerAddress)) {
            throw new Exception("Withdrawn from wrong address");
        }

        UFix64NumberField amountTo = (UFix64NumberField) secondEvent.getField("amount");
        if (!amountTo.toBigDecimal().equals(amount)) {
            throw new Exception("Deposited FUSD amount not match");
        }

        AddressField to = (AddressField) secondEvent.getField("to").getValue();
        if (!to.getValue().toString().substring(2).equals(this.accountAddress)) {
            throw new Exception("Deposited to wrong address");
        }
    }

    public FlowAddress createAccount(FlowAddress payerAddress, String publicKeyHex) {
        FlowAccountKey payerAccountKey = this.getAccountKey(payerAddress, 0);
        FlowAccountKey newAccountPublicKey = new FlowAccountKey(0, new FlowPublicKey(publicKeyHex),
                SignatureAlgorithm.ECDSA_P256, HashAlgorithm.SHA2_256, 1, 0, false);

        FlowTransaction tx = new FlowTransaction(new FlowScript(loadScript("create_account.cdc")),
                Arrays.asList(new FlowArgument(new StringField(Hex.toHexString(newAccountPublicKey.getEncoded())))),
                this.getLatestBlockID(), 100L,
                new FlowTransactionProposalKey(payerAddress, payerAccountKey.getId(),
                        payerAccountKey.getSequenceNumber()),
                payerAddress, Arrays.asList(payerAddress), new ArrayList<>(), new ArrayList<>());

        Signer signer = Crypto.getSigner(this.privateKey, payerAccountKey.getHashAlgo());
        tx = tx.addPayloadSignature(payerAddress, 0, signer);
        tx = tx.addEnvelopeSignature(payerAddress, 0, signer);

        FlowId txID = this.accessAPI.sendTransaction(tx);
        FlowTransactionResult txResult = this.waitForSeal(txID);

        return this.getAccountCreatedAddress(txResult);
    }

    public void transferTokens(FlowAddress senderAddress, FlowAddress recipientAddress, BigDecimal amount)
            throws Exception {
        // exit early
        if (amount.scale() != 8) {
            throw new Exception("FLOW amount must have exactly 8 decimal places of precision (e.g. 10.00000000)");
        }

        FlowAccountKey senderAccountKey = this.getAccountKey(senderAddress, 0);
        FlowTransaction tx = new FlowTransaction(new FlowScript(loadScript("transfer_flow.cdc")),
                Arrays.asList(new FlowArgument(new UFix64NumberField(amount.toPlainString())),
                        new FlowArgument(new AddressField(recipientAddress.getBase16Value()))),
                this.getLatestBlockID(), 100L,
                new FlowTransactionProposalKey(senderAddress, senderAccountKey.getId(),
                        senderAccountKey.getSequenceNumber()),
                senderAddress, Arrays.asList(senderAddress), new ArrayList<>(), new ArrayList<>());

        Signer signer = Crypto.getSigner(this.privateKey, senderAccountKey.getHashAlgo());
        tx = tx.addEnvelopeSignature(senderAddress, senderAccountKey.getId(), signer);

        FlowId txID = this.accessAPI.sendTransaction(tx);
        this.waitForSeal(txID);
    }

    public FlowAccount getAccount(FlowAddress address) {
        FlowAccount ret = this.accessAPI.getAccountAtLatestBlock(address);
        return ret;
    }

    public BigDecimal getAccountBalance(FlowAddress address) {
        FlowAccount account = this.getAccount(address);
        return account.getBalance();
    }

    private FlowId getLatestBlockID() {
        return this.accessAPI.getLatestBlockHeader().getId();
    }

    private FlowAccountKey getAccountKey(FlowAddress address, int keyIndex) {
        FlowAccount account = this.getAccount(address);
        return account.getKeys().get(keyIndex);
    }

    private FlowTransactionResult getTransactionResult(FlowId txID) {
        FlowTransactionResult result = this.accessAPI.getTransactionResultById(txID);
        return result;
    }

    private FlowTransactionResult waitForSeal(FlowId txID) {
        FlowTransactionResult txResult;

        while (true) {
            txResult = this.getTransactionResult(txID);
            if (txResult.getStatus().equals(FlowTransactionStatus.SEALED)) {
                return txResult;
            }

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private FlowAddress getAccountCreatedAddress(FlowTransactionResult txResult) {
        if (!txResult.getStatus().equals(FlowTransactionStatus.SEALED) || txResult.getErrorMessage().length() > 0) {
            return null;
        }

        String rez = txResult.getEvents().get(0).getEvent().getValue().getFields()[0].getValue().getValue().toString();
        return new FlowAddress(rez.substring(2));
    }

    private byte[] loadScript(String name) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(name);) {
            return is.readAllBytes();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private String readScript(String name) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(name);) {

            return IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

}
