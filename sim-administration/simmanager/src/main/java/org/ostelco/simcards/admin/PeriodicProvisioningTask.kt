package org.ostelco.simcards.admin

import com.google.common.collect.ImmutableMultimap
import io.dropwizard.servlets.tasks.Task
import org.ostelco.simcards.inventory.SimInventoryDAO
import java.io.PrintWriter


class PreallocateProfiles(val simInventoryDAO: SimInventoryDAO) : Task("preallocate_sim_profiles") {

    @Throws(Exception::class)
    override fun execute(parameters: ImmutableMultimap<String, String>, output: PrintWriter) {

        /* TODO: This is commented out just to  permit merging of the rest of the  branch
           to develop. Laziness & convenience.
        var hlrs : Collection<String> = simInventoryDAO.getListOfHLRs()

        for (hlr in hlrs) {
            for (profile in simInventory.getProfile)
        }
*/

        // TODO:
        // * List all of the HLRs
        // * For each HLR, figure out
        //    - How many profiles are available for respectively android and default
        //    - If the numbers are below low-water marks, preallocate a chunk.  That chunk
        //      should be sufficiently small to ensure that the task will terminate in a reasonable
        //      amount of time
        //    - If the amount of available SIMs are getting low (for some definition of low), then
        //      sound the alert via logging or some other method.
    }
}