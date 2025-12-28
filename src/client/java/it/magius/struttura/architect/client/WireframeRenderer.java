package it.magius.struttura.architect.client;

import it.magius.struttura.architect.network.WireframeData;

/**
 * Renderizza i wireframe per costruzioni e selezioni.
 *
 * TODO: Implementare rendering visivo con la nuova API MC 1.21.11
 * L'API di rendering è stata completamente riscritta in MC 1.21.9+
 * e richiede l'uso di RenderPipelines e il nuovo sistema a due fasi
 * (extraction/drawing). Fabric API 0.140+ ha reintrodotto WorldRenderEvents
 * nel package net.fabricmc.fabric.api.client.rendering.v1.world
 */
public class WireframeRenderer {

    // Dati correnti (aggiornati dal packet)
    private static WireframeData.ConstructionWireframe constructionData = WireframeData.ConstructionWireframe.empty();
    private static WireframeData.SelectionWireframe selectionData = WireframeData.SelectionWireframe.empty();

    /**
     * Aggiorna i dati del wireframe costruzione.
     */
    public static void setConstructionData(WireframeData.ConstructionWireframe data) {
        constructionData = data;
    }

    /**
     * Aggiorna i dati del wireframe selezione.
     */
    public static void setSelectionData(WireframeData.SelectionWireframe data) {
        selectionData = data;
    }

    /**
     * Ottiene i dati del wireframe costruzione (per eventuale debug/test).
     */
    public static WireframeData.ConstructionWireframe getConstructionData() {
        return constructionData;
    }

    /**
     * Ottiene i dati del wireframe selezione (per eventuale debug/test).
     */
    public static WireframeData.SelectionWireframe getSelectionData() {
        return selectionData;
    }

    /**
     * Resetta tutti i dati (es. quando si esce dal mondo).
     */
    public static void reset() {
        constructionData = WireframeData.ConstructionWireframe.empty();
        selectionData = WireframeData.SelectionWireframe.empty();
    }
}
