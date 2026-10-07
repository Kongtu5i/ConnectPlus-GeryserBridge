package dev.connectplus.geyserbridge.identity;

import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.network.AuthType;
import org.geysermc.geyser.session.GeyserSession;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录真实的认证完成状态并排除不支持的运行模式（协议第 3 节、第 5 节）。
 *
 * 关键事实（以锁定版本源码为准，见 README"版本验证记录"）：
 *  - {@code LoginEncryptionUtils#encryptConnectionWithCert} 同步校验 Xbox 身份链并把
 *    {@code authData}（含 XUID）写入会话；只有当 {@code validateBedrockLogin=true} 时
 *    才拒绝未签名链，因此该配置为信任前提之一。
 *  - 加密握手（密钥持有证明）由 bedrock 协议库在登录包之后完成；下游 c2p 连接
 *    只可能在客户端通过加密与资源包流程之后建立，因此"能匹配到下游连接"本身
 *    即隐含密钥持有证明。重复登录准入路径（更早）的密钥证明由 KeyProofGate 单独跟踪。
 */
public final class GeyserIdentityVerifier {

    private final AuthType authType;
    private final boolean validateBedrockLogin;
    private final boolean waterdogForwarding;

    private GeyserIdentityVerifier(AuthType authType, boolean validateBedrockLogin, boolean waterdogForwarding) {
        this.authType = authType;
        this.validateBedrockLogin = validateBedrockLogin;
        this.waterdogForwarding = waterdogForwarding;
    }

    public static GeyserIdentityVerifier snapshot() {
        GeyserImpl geyser = GeyserImpl.getInstance();
        var config = geyser.config();
        return new GeyserIdentityVerifier(
                config.java().authType(),
                config.advanced().bedrock().validateBedrockLogin(),
                config.advanced().bedrock().useWaterdogpeForwarding());
    }

    /** @return 空列表表示运行模式受支持；否则给出停用原因（诊断日志用）。 */
    public List<String> runtimeProblems() {
        List<String> problems = new ArrayList<>();
        if (waterdogForwarding) {
            problems.add("useWaterdogpeForwarding must be disabled: external identity forwarding is out of scope");
        }
        if (!validateBedrockLogin) {
            problems.add("validateBedrockLogin must be enabled: unsigned identity chains are not trusted");
        }
        if (authType == AuthType.FLOODGATE) {
            problems.add("authType=FLOODGATE routes identity through Floodgate, which is out of scope");
        }
        return problems;
    }

    /**
     * @return true 当该会话已经通过 Geyser 的 Xbox 身份链校验（authData 已写入）。
     * 运行时配置为"要求认证"不证明某条已有会话完成了认证，必须逐会话检查。
     */
    public boolean hasValidatedIdentity(GeyserSession session) {
        return session != null && !session.isClosed() && session.getAuthData() != null;
    }

    /**
     * RESOLVE 用的完整信任判定：身份已校验 + 运行模式受支持 + 会话存活。
     * 密钥持有证明由"下游连接存在"这一匹配前提隐含（见类注释）。
     */
    public boolean isTrustworthy(GeyserSession session) {
        return runtimeProblems().isEmpty() && hasValidatedIdentity(session);
    }

    public AuthType authType() {
        return authType;
    }
}
