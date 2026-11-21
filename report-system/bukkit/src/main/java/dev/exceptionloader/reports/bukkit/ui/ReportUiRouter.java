package dev.exceptionloader.reports.bukkit.ui;

import dev.exceptionloader.reports.bukkit.bedrock.BedrockGateway;
import dev.exceptionloader.reports.bukkit.ui.menu.MenuReportUi;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bukkit.entity.Player;

@Singleton
public class ReportUiRouter {

    private final BedrockGateway bedrock;
    private final ReportUi java;
    private final ReportUi bedrockUi;

    @Inject
    public ReportUiRouter(BedrockGateway bedrock, MenuReportUi java, @BedrockUi ReportUi bedrockUi) {
        this.bedrock = bedrock;
        this.java = java;
        this.bedrockUi = bedrockUi;
    }

    public ReportUi of(Player player) {
        return bedrock.isBedrock(player.getUniqueId()) ? bedrockUi : java;
    }
}
