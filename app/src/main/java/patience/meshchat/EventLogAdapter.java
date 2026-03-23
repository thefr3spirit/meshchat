package patience.meshchat;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * RecyclerView adapter for the event log in NetworkFragment.
 * Each item shows a broadcast event (airplane mode, BT state, etc.)
 * with a colored dot and timestamp.
 */
public class EventLogAdapter extends RecyclerView.Adapter<EventLogAdapter.ViewHolder> {

    private final List<ConnectivityObserver.Event> events;

    public EventLogAdapter(List<ConnectivityObserver.Event> events) {
        this.events = events;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_event_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ConnectivityObserver.Event event = events.get(position);
        holder.eventText.setText(event.description);
        holder.eventTime.setText(event.getFormattedTime());

        // Color the dot based on event content
        GradientDrawable dot = (GradientDrawable) holder.eventDot.getBackground();
        int color;
        String desc = event.description.toLowerCase();
        if (desc.contains("on") && !desc.contains("off")) {
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.statusOnline);
        } else if (desc.contains("off") || desc.contains("lost")) {
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.statusOffline);
        } else {
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.statusIdle);
        }
        dot.setColor(color);
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View eventDot;
        final TextView eventText;
        final TextView eventTime;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            eventDot = itemView.findViewById(R.id.eventDot);
            eventText = itemView.findViewById(R.id.eventText);
            eventTime = itemView.findViewById(R.id.eventTime);
        }
    }
}
