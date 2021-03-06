package io.javaoperatorsdk.operator;

import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.CustomResourceEvent;
import io.javaoperatorsdk.operator.processing.EventDispatcher;
import io.javaoperatorsdk.operator.processing.retry.GenericRetry;
import io.javaoperatorsdk.operator.sample.TestCustomResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.Watcher;
import io.javaoperatorsdk.operator.api.Controller;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EventDispatcherTest {

    public static final String FINALIZER_NAME = "finalizer";
    private CustomResource testCustomResource;
    private EventDispatcher eventDispatcher;
    private final ResourceController<CustomResource> controller = mock(ResourceController.class);
    private final EventDispatcher.CustomResourceFacade customResourceFacade = mock(EventDispatcher.CustomResourceFacade.class);

    @BeforeEach
    void setup() {
        eventDispatcher = new EventDispatcher(controller,
                FINALIZER_NAME, customResourceFacade, false);

        testCustomResource = getResource();

        when(controller.createOrUpdateResource(eq(testCustomResource), any())).thenReturn(UpdateControl.updateCustomResource(testCustomResource));
        when(controller.deleteResource(eq(testCustomResource), any())).thenReturn(true);
        when(customResourceFacade.replaceWithLock(any())).thenReturn(null);
    }

    @Test
    void callCreateOrUpdateOnNewResource() {
        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.ADDED, testCustomResource));
        verify(controller, times(1)).createOrUpdateResource(ArgumentMatchers.eq(testCustomResource), any());
    }

    @Test
    void updatesOnlyStatusSubResource() {
        testCustomResource.getMetadata().getFinalizers().add(FINALIZER_NAME);
        when(controller.createOrUpdateResource(eq(testCustomResource), any()))
                .thenReturn(UpdateControl.updateStatusSubResource(testCustomResource));

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.ADDED, testCustomResource));

        verify(customResourceFacade, times(1)).updateStatus(testCustomResource);
        verify(customResourceFacade, never()).replaceWithLock(any());
    }


    @Test
    void callCreateOrUpdateOnModifiedResource() {
        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));
        verify(controller, times(1)).createOrUpdateResource(ArgumentMatchers.eq(testCustomResource), any());
    }

    @Test
    void addsFinalizerOnCreateIfNotThere() {
        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));
        verify(controller, times(1))
                .createOrUpdateResource(argThat(testCustomResource ->
                        testCustomResource.getMetadata().getFinalizers().contains(FINALIZER_NAME)), any());
    }

    @Test
    void callsDeleteIfObjectHasFinalizerAndMarkedForDelete() {
        testCustomResource.getMetadata().setDeletionTimestamp("2019-8-10");
        testCustomResource.getMetadata().getFinalizers().add(FINALIZER_NAME);

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller, times(1)).deleteResource(eq(testCustomResource), any());
    }

    /**
     * Note that there could be more finalizers. Out of our control.
     */
    @Test
    void callDeleteOnControllerIfMarkedForDeletionButThereIsNoFinalizer() {
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller).deleteResource(eq(testCustomResource), any());
    }

    @Test
    void removesFinalizerOnDelete() {
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        assertEquals(0, testCustomResource.getMetadata().getFinalizers().size());
        verify(customResourceFacade, times(1)).replaceWithLock(any());
    }

    @Test
    void doesNotRemovesTheFinalizerIfTheDeleteMethodRemovesFalse() {
        when(controller.deleteResource(eq(testCustomResource), any())).thenReturn(false);
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        assertEquals(1, testCustomResource.getMetadata().getFinalizers().size());
        verify(customResourceFacade, never()).replaceWithLock(any());
    }

    @Test
    void doesNotUpdateTheResourceIfNoUpdateUpdateControl() {
        when(controller.createOrUpdateResource(eq(testCustomResource), any())).thenReturn(UpdateControl.noUpdate());

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));
        verify(customResourceFacade, never()).replaceWithLock(any());
        verify(customResourceFacade, never()).updateStatus(testCustomResource);
    }

    @Test
    void addsFinalizerIfNotMarkedForDeletionAndEmptyCustomResourceReturned() {
        removeFinalizers(testCustomResource);
        when(controller.createOrUpdateResource(eq(testCustomResource), any())).thenReturn(UpdateControl.noUpdate());

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        assertEquals(1, testCustomResource.getMetadata().getFinalizers().size());
        verify(customResourceFacade, times(1)).replaceWithLock(any());
    }

    @Test
    void doesNotCallDeleteIfMarkedForDeletionButNotOurFinalizer() {
        removeFinalizers(testCustomResource);
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(customResourceFacade, never()).replaceWithLock(any());
        verify(controller, never()).deleteResource(eq(testCustomResource), any());
    }

    @Test
    void skipsControllerExecutionOnIfGenerationAwareModeIfNotLargerGeneration() {
        generationAwareMode();

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));
        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller, times(1)).createOrUpdateResource(eq(testCustomResource), any());
    }

    @Test
    void skipsExecutesControllerOnGenerationIncrease() {
        generationAwareMode();

        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));
        testCustomResource.getMetadata().setGeneration(testCustomResource.getMetadata().getGeneration() + 1);
        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller, times(2)).createOrUpdateResource(eq(testCustomResource), any());
    }

    @Test
    void doesNotMarkNewGenerationInCaseOfException() {
        generationAwareMode();
        when(controller.createOrUpdateResource(any(), any()))
                .thenThrow(new IllegalStateException("Exception for testing purposes"))
                .thenReturn(UpdateControl.noUpdate());

        Assertions.assertThrows(IllegalStateException.class, () ->
                eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource)));
        eventDispatcher.handleEvent(customResourceEvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller, times(2)).createOrUpdateResource(eq(testCustomResource), any());

    }

    void generationAwareMode() {
        eventDispatcher = new EventDispatcher(controller,
                FINALIZER_NAME, customResourceFacade, true);
    }

    private void markForDeletion(CustomResource customResource) {
        customResource.getMetadata().setDeletionTimestamp("2019-8-10");
    }

    private void removeFinalizers(CustomResource customResource) {
        customResource.getMetadata().getFinalizers().clear();
    }

    CustomResource getResource() {
        TestCustomResource resource = new TestCustomResource();
        resource.setMetadata(new ObjectMetaBuilder()
                .withClusterName("clusterName")
                .withCreationTimestamp("creationTimestamp")
                .withDeletionGracePeriodSeconds(10L)
                .withGeneration(10L)
                .withName("name")
                .withFinalizers(FINALIZER_NAME)
                .withNamespace("namespace")
                .withResourceVersion("resourceVersion")
                .withSelfLink("selfLink")
                .withUid("uid").build());
        return resource;
    }

    public CustomResourceEvent customResourceEvent(Watcher.Action action, CustomResource resource) {
        return new CustomResourceEvent(action, resource, GenericRetry.defaultLimitedExponentialRetry());
    }
}
