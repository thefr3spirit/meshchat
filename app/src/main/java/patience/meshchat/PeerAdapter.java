package patience.meshchat;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * PeerAdapter — RecyclerView adapter for discovered Bluetooth peers.
 * Used in NetworkDiscoveryFragment's bottom sheet to show devices found
 * in the same mesh network during an active scan.
 */
public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.PeerViewHolder> {

    /** Data class representing a discovered Bluetooth peer */
    public static class PeerInfo {
        public final String name;
        public final String address;
        public final boolean isConnected;
        public final String type;   // always "bluetooth" now
        public final int rssi;      // signal strength in dBm

        public PeerInfo(String name, String address, boolean isConnected,
                        String type, int rssi) {
            this.name = name;
            this.address = address;
            this.isConnected = isConnected;
            this.type = type;
            this.rssi = rssi;
        }

        /** Legacy constructor without RSSI */
        public PeerInfo(String name, String address, boolean isConnected, String type) {
            this(name, address, isConnected, type, Integer.MIN_VALUE);
        }
    }

    public interface OnPeerClickListener {
        void onConnectClicked(PeerInfo peer);
    }

    private final List<PeerInfo> peers;
    private final OnPeerClickListener listener;

    public PeerAdapter(List<PeerInfo> peers, OnPeerClickListener listener) {
        this.peers = peers;
        this.listener = listener;
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer, parent, false);
        return new PeerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        holder.bind(peers.get(position));
    }

    @Override
    public int getItemCount() { return peers.size(); }

    class PeerViewHolder extends RecyclerView.ViewHolder {
        private final TextView peerName;
        private final TextView peerAddress;
        private final View statusDot;
        private final MaterialButton connectButton;

        PeerViewHolder(View itemView) {
            super(itemView);
            peerName = itemView.findViewById(R.id.peerName);
            peerAddress = itemView.findViewById(R.id.peerAddress);
            statusDot = itemView.findViewById(R.id.statusDot);
            connectButton = itemView.findViewById(R.id.connectButton);
        }

        void bind(PeerInfo peer) {
            String displayName = peer.name;
            if (displayName != null && displayName.startsWith("MC_")) {
                displayName = displayName.substring(3);
            } else if (displayName != null && displayName.startsWith("MeshChat_")) {
                displayName = displayName.substring("MeshChat_".length());
            }
            if (displayName == null || displayName.isEmpty()) displayName = "Unknown Device";
            peerName.setText(displayName);

            String rssiLabel = peer.rssi != Integer.MIN_VALUE ? peer.rssi + " dBm" : "";
            peerAddress.setText(peer.address + (rssiLabel.isEmpty() ? "" : " · " + rssiLabel));

            GradientDrawable dot = (GradientDrawable) statusDot.getBackground();
            if (peer.isConnected) {
                dot.setColor(itemView.getContext().getColor(R.color.peerConnected));
                connectButton.setText("Connected");
                connectButton.setEnabled(false);
            } else {
                dot.setColor(itemView.getContext().getColor(R.color.peerAvailable));
                connectButton.setText("Connect");
                connectButton.setEnabled(true);
            }

            connectButton.setOnClickListener(v -> {
                if (listener != null && !peer.isConnected) listener.onConnectClicked(peer);
            });
            itemView.setOnClickListener(v -> {
                if (listener != null && !peer.isConnected) listener.onConnectClicked(peer);
            });
        }
    }
}
