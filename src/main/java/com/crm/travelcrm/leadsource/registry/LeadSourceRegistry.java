package com.crm.travelcrm.leadsource.registry;

import com.crm.travelcrm.leadsource.spi.ChannelCatalogEntry;
import com.crm.travelcrm.leadsource.spi.LeadSourceAdapter;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.leadsource.spi.LeadSourceFetcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every deployed {@link LeadSourceAdapter} and {@link LeadSourceFetcher}, folded once at construction
 * and keyed by {@code channel.slug()}.
 *
 * <p><b>Eager fold, not a stream filter per request.</b> The obvious idiom — stream the bean list and
 * filter on each call — is an O(n) scan on a PUBLIC hot path, and, decisively, <b>a stream filter
 * cannot detect a duplicate claim at all</b>.
 *
 * <p><b>Deviation 1: throws on a duplicate slug.</b> The resolver precedent in this codebase does a
 * bare {@code map.put(key, bean)}, so two beans claiming one key is last-one-wins by Spring's bean
 * ordering, silently. For OTP that is a wrong-provider bug; here it would be <b>wrong-tenant
 * attribution on a public endpoint</b>. Spring will not catch it — a {@code List<T>} holds both
 * cheerfully.
 *
 * <p><b>Deviation 2: {@link ObjectProvider}, not a bare {@code List<T>}.</b> A required
 * {@code List<T>} constructor parameter with zero candidate beans does not inject an empty list — it
 * fails with {@code NoSuchBeanDefinitionException}. Phase 1 ships one adapter and <b>zero fetchers</b>
 * (no fetcher exists until META_ADS), so a bare {@code List<LeadSourceFetcher>} could not boot the
 * framework in its own first phase. The OTP resolver gets away with a bare list only because a stub
 * bean is always present; there is no such stub here.
 *
 * <p><b>The registry is the channel constraint, and a stricter one than a DB CHECK.</b> It rejects an
 * unknown slug at save time AND again at ingest, whereas a CHECK only validates spelling — it would
 * happily accept a {@code meta_ads} row on a node with no Meta adapter deployed.
 */
@Component
public class LeadSourceRegistry {

    private static final Logger log = LogManager.getLogger(LeadSourceRegistry.class);

    private final Map<String, LeadSourceAdapter> adapters = new HashMap<>();
    private final Map<String, LeadSourceFetcher> fetchers = new HashMap<>();

    public LeadSourceRegistry(ObjectProvider<List<LeadSourceAdapter>> adapterProvider,
                              ObjectProvider<List<LeadSourceFetcher>> fetcherProvider) {

        for (LeadSourceAdapter a : adapterProvider.getIfAvailable(List::of)) {
            String slug = a.channel().slug();
            LeadSourceAdapter existing = adapters.put(slug, a);
            if (existing != null) {
                throw new IllegalStateException(String.format(
                        "Two LeadSourceAdapters claim channel '%s': %s and %s. One of them would "
                                + "silently win by bean ordering and attribute leads to the wrong "
                                + "connection.",
                        slug, existing.getClass().getName(), a.getClass().getName()));
            }
        }

        for (LeadSourceFetcher f : fetcherProvider.getIfAvailable(List::of)) {
            String slug = f.channel().slug();
            LeadSourceFetcher existing = fetchers.put(slug, f);
            if (existing != null) {
                throw new IllegalStateException(String.format(
                        "Two LeadSourceFetchers claim channel '%s': %s and %s.",
                        slug, existing.getClass().getName(), f.getClass().getName()));
            }
        }

        log.info("Lead-source registry: {} adapter(s) {}, {} fetcher(s) {}.",
                adapters.size(), adapters.keySet(), fetchers.size(), fetchers.keySet());
    }

    /**
     * @throws IllegalStateException when no adapter is deployed for the channel — a programming error
     *                               by this point: the ingest path resolves the slug through
     *                               {@link #findAdapter} and 404s before ever getting here.
     */
    public LeadSourceAdapter adapterFor(LeadSourceChannel channel) {
        LeadSourceAdapter a = adapters.get(channel.slug());
        if (a == null) {
            throw new IllegalStateException("No LeadSourceAdapter deployed for channel " + channel.slug());
        }
        return a;
    }

    /**
     * Lookup that can miss without raising — for the ingest path, where an unknown or undeployed
     * channel must 404 with zero database work rather than throw.
     */
    public Optional<LeadSourceAdapter> findAdapter(LeadSourceChannel channel) {
        return Optional.ofNullable(adapters.get(channel.slug()));
    }

    /**
     * @throws IllegalStateException when a {@code Deferred} parse has no fetcher to drain it. Reaching
     *                               this means an adapter returned {@code Deferred} for a channel that
     *                               ships no fetcher — the lead would be stranded, so failing loudly
     *                               beats losing it quietly.
     */
    public LeadSourceFetcher fetcherFor(LeadSourceChannel channel) {
        LeadSourceFetcher f = fetchers.get(channel.slug());
        if (f == null) {
            throw new IllegalStateException(
                    "No LeadSourceFetcher deployed for channel " + channel.slug()
                            + ", but its adapter deferred a lead to be fetched.");
        }
        return f;
    }

    public Optional<LeadSourceFetcher> findFetcher(LeadSourceChannel channel) {
        return Optional.ofNullable(fetchers.get(channel.slug()));
    }

    /** Channels this node can actually serve — what the Integrations grid renders. */
    public List<LeadSourceChannel> deployedChannels() {
        return java.util.Arrays.stream(LeadSourceChannel.values())
                .filter(c -> adapters.containsKey(c.slug()))
                .toList();
    }

    /** The catalog for the Integrations UI, in channel declaration order. */
    public List<ChannelCatalogEntry> catalog() {
        return deployedChannels().stream().map(c -> adapters.get(c.slug()).catalog()).toList();
    }
}
