package patience.meshchat.transport;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

import javax.inject.Singleton;

/**
 * ============================================================================
 * TransportModule — Hilt DI Module for Transport Layer
 * ============================================================================
 *
 * Tells Hilt how to provide the CompositeTransport as the single
 * MeshTransport that the rest of the app depends on.
 *
 * WHY @Binds IS NOT USED HERE:
 *   CompositeTransport already has an @Inject constructor, so Hilt can
 *   create it automatically.  This module simply binds the MeshTransport
 *   interface to the CompositeTransport concrete class, so that any code
 *   requesting MeshTransport gets the composite.
 *
 * ============================================================================
 */
@Module
@InstallIn(SingletonComponent.class)
public abstract class TransportModule {

    /**
     * Bind the MeshTransport interface to CompositeTransport.
     * Any @Inject MeshTransport field will receive the CompositeTransport.
     */
    @dagger.Binds
    @Singleton
    abstract MeshTransport bindMeshTransport(CompositeTransport impl);
}
